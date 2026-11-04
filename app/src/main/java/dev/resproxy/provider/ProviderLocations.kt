package dev.resproxy.provider

import dev.resproxy.data.Account
import dev.resproxy.data.Provider
import dev.resproxy.net.Resolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A selectable location; codes are what goes into the proxy credentials (`country-us`, `state-iowa`, …). */
@Serializable
data class Place(
    val code: String,
    val name: String,
    val states: List<Place> = emptyList(),
    val cities: List<Place> = emptyList(),
)

enum class CatalogSource { BUILT_IN, CACHED, LIVE }

data class LocationCatalog(
    val countries: List<Place>,
    val source: CatalogSource,
    val fetchedAt: Long = 0,
    val error: String? = null,
    val loading: Boolean = false,
) {
    fun country(code: String) = countries.firstOrNull { it.code == code }
}

/**
 * A provider's targetable countries / states / cities, fetched from its API and cached for a day.
 * Without usable credentials it falls back to ISO countries + US states (cities become free text).
 */
class ProviderLocations(private val provider: Provider, private val cacheFile: File) {

    private val http = OkHttpClient.Builder().dns(Resolver).callTimeout(30, TimeUnit.SECONDS).build()
    private val listSerializer = ListSerializer(Place.serializer())

    private val _catalog = MutableStateFlow(loadCache() ?: builtIn())
    val catalog: StateFlow<LocationCatalog> = _catalog.asStateFlow()

    /** Whether [account] has what this provider's location API needs. */
    fun canFetch(account: Account?) = when (provider) {
        Provider.IPROYAL -> !account?.apiToken.isNullOrBlank()
        // Dashboard login if set, else the proxy credentials (Geonode's API takes either as Basic auth).
        Provider.GEONODE -> account != null && geonodeAuth(account) != null
        Provider.GENERIC -> false
    }

    /** Refreshes from the API when possible and the cache is older than a day (or [force]). */
    suspend fun refresh(account: Account?, force: Boolean = false) {
        if (!canFetch(account)) {
            if (_catalog.value.source != CatalogSource.BUILT_IN) _catalog.value = builtIn()
            return
        }
        val cur = _catalog.value
        if (!force && cur.source != CatalogSource.BUILT_IN && System.currentTimeMillis() - cur.fetchedAt < DAY_MS) return
        _catalog.update { it.copy(loading = true, error = null) }
        try {
            val countries = withContext(Dispatchers.IO) { parse(provider, fetch(account!!)) }
            if (countries.isEmpty()) throw IOException("${provider.label} returned no locations")
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                runCatching { cacheFile.writeText("$now\n" + json.encodeToString(listSerializer, countries)) }
            }
            _catalog.value = LocationCatalog(countries, CatalogSource.LIVE, now)
        } catch (e: Exception) {
            _catalog.update { it.copy(loading = false, error = e.message ?: "Could not load locations") }
        }
    }

    private fun fetch(account: Account): String {
        val req = when (provider) {
            Provider.IPROYAL -> Request.Builder().url(IPROYAL_URL).header("Authorization", "Bearer ${account.apiToken.trim()}")
            else -> Request.Builder().url(GEONODE_URL).header("Authorization", geonodeAuth(account)!!.let { (u, p) -> Credentials.basic(u, p) })
        }.header("Accept", "application/json").build()
        return http.newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 403) {
                throw IOException(
                    if (provider == Provider.IPROYAL) "IPRoyal rejected the API token"
                    else if (account.apiLogin.isNotBlank()) "Geonode rejected the dashboard login used for the location list"
                    else "Geonode didn't accept the proxy login for the location list. Add your dashboard login in Settings → Accounts."
                )
            }
            if (!r.isSuccessful) throw IOException("${provider.label} API error ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    private fun loadCache(): LocationCatalog? = runCatching {
        val text = cacheFile.takeIf { it.exists() }?.readText() ?: return null
        val ts = text.substringBefore('\n').toLong()
        LocationCatalog(json.decodeFromString(listSerializer, text.substringAfter('\n')), CatalogSource.CACHED, ts)
    }.getOrNull()

    companion object {
        private const val IPROYAL_URL = "https://resi-api.iproyal.com/v1/access/countries"
        private const val GEONODE_URL = "https://monitor.geonode.com/services/RESIDENTIAL-PREMIUM/targeting-options"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Basic-auth pair for Geonode's API: the dashboard login, or the base proxy username + password. */
        fun geonodeAuth(a: Account): Pair<String, String>? = when {
            a.apiLogin.isNotBlank() && a.apiPassword.isNotBlank() -> a.apiLogin.trim() to a.apiPassword.trim()
            a.username.isNotBlank() && a.password.isNotBlank() -> a.username.trim().substringBefore('-') to a.password.trim()
            else -> null
        }

        fun parse(provider: Provider, body: String): List<Place> = when (provider) {
            Provider.IPROYAL -> parseIpRoyal(body)
            else -> parseGeonode(body)
        }

        /** IPRoyal `/v1/access/countries`: options lists wrapped in `{prefix, options}`; ISPs dropped. */
        fun parseIpRoyal(body: String): List<Place> {
            val root = json.parseToJsonElement(body).jsonObject
            return array(root["countries"]).map { c ->
                Place(
                    code = c.str("code"), name = c.str("name"),
                    states = array(c.obj("states")?.get("options")).map { s ->
                        Place(s.str("code"), s.str("name"), cities = places(s.obj("cities")?.get("options")))
                    }.sortedBy { it.name },
                    cities = places(c.obj("cities")?.get("options")),
                )
            }.filter { it.code.isNotEmpty() }.sortedBy { it.name }
        }

        /** Geonode `targeting-options`: plain arrays, cities nested under states; ASNs dropped. */
        fun parseGeonode(body: String): List<Place> {
            val root = json.parseToJsonElement(body).jsonObject
            return array(root["countries"]).map { c ->
                val states = array(c["states"]).map { s -> Place(s.str("code").lowercase(), s.str("name"), cities = places(s["cities"])) }
                    .filter { it.code.isNotEmpty() }.sortedBy { it.name }
                Place(
                    code = c.str("code").lowercase(), name = c.str("name"), states = states,
                    // "Any state" + city: offer every city in the country.
                    cities = states.flatMap { it.cities }.distinctBy { it.code }.sortedBy { it.name },
                )
            }.filter { it.code.isNotEmpty() }.sortedBy { it.name }
        }

        private fun places(e: JsonElement?) =
            array(e).map { Place(it.str("code").lowercase(), it.str("name")) }.filter { it.code.isNotEmpty() }.sortedBy { it.name }

        private fun array(e: JsonElement?): List<JsonObject> = (e as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        private fun JsonObject.obj(k: String) = this[k] as? JsonObject
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

        fun builtIn(): LocationCatalog {
            val countries = Locale.getISOCountries().map { cc ->
                val code = cc.lowercase()
                Place(code, Locale("", cc).getDisplayCountry(Locale.ENGLISH), states = if (code == "us") US_STATES else emptyList())
            }.sortedBy { it.name }
            return LocationCatalog(countries, CatalogSource.BUILT_IN)
        }

        private val US_STATES = listOf(
            "Alabama", "Alaska", "Arizona", "Arkansas", "California", "Colorado", "Connecticut", "Delaware",
            "District of Columbia", "Florida", "Georgia", "Hawaii", "Idaho", "Illinois", "Indiana", "Iowa", "Kansas",
            "Kentucky", "Louisiana", "Maine", "Maryland", "Massachusetts", "Michigan", "Minnesota", "Mississippi",
            "Missouri", "Montana", "Nebraska", "Nevada", "New Hampshire", "New Jersey", "New Mexico", "New York",
            "North Carolina", "North Dakota", "Ohio", "Oklahoma", "Oregon", "Pennsylvania", "Rhode Island",
            "South Carolina", "South Dakota", "Tennessee", "Texas", "Utah", "Vermont", "Virginia", "Washington",
            "West Virginia", "Wisconsin", "Wyoming",
        ).map { Place(it.lowercase().replace(" ", ""), it) }
    }
}
