package dev.resproxy.provider

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import dev.resproxy.net.Resolver
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A selectable location; codes are what goes into the proxy password (`_country-us`, `_state-iowa`, …). */
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
 * IPRoyal's targetable countries / states / cities. The live list needs the dashboard API token;
 * without one we fall back to ISO countries + US states (cities then become free text).
 */
class IpRoyalLocations(private val cacheFile: File) {

    private val http = OkHttpClient.Builder().dns(Resolver).callTimeout(30, TimeUnit.SECONDS).build()
    private val listSerializer = ListSerializer(Place.serializer())

    private val _catalog = MutableStateFlow(loadCache() ?: builtIn())
    val catalog: StateFlow<LocationCatalog> = _catalog.asStateFlow()

    /** Refreshes from the API when a token is set and the cache is older than a day (or [force]). */
    suspend fun refresh(apiToken: String, force: Boolean = false) {
        val token = apiToken.trim()
        if (token.isEmpty()) {
            if (_catalog.value.source != CatalogSource.BUILT_IN) _catalog.value = builtIn()
            return
        }
        val cur = _catalog.value
        if (!force && cur.source != CatalogSource.BUILT_IN && System.currentTimeMillis() - cur.fetchedAt < DAY_MS) return
        _catalog.update { it.copy(loading = true, error = null) }
        try {
            val body = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(URL).header("Authorization", "Bearer $token").header("Accept", "application/json").build()
                http.newCall(req).execute().use { r ->
                    if (r.code == 401 || r.code == 403) throw IOException("IPRoyal rejected the API token")
                    if (!r.isSuccessful) throw IOException("IPRoyal API error ${r.code}")
                    r.body?.string().orEmpty()
                }
            }
            val countries = parse(body)
            if (countries.isEmpty()) throw IOException("IPRoyal returned no locations")
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                runCatching { cacheFile.writeText("$now\n" + json.encodeToString(listSerializer, countries)) }
            }
            _catalog.value = LocationCatalog(countries, CatalogSource.LIVE, now)
        } catch (e: Exception) {
            _catalog.update { it.copy(loading = false, error = e.message ?: "Could not load locations") }
        }
    }

    private fun loadCache(): LocationCatalog? = runCatching {
        val text = cacheFile.takeIf { it.exists() }?.readText() ?: return null
        val ts = text.substringBefore('\n').toLong()
        LocationCatalog(json.decodeFromString(listSerializer, text.substringAfter('\n')), CatalogSource.CACHED, ts)
    }.getOrNull()

    companion object {
        private const val URL = "https://resi-api.iproyal.com/v1/access/countries"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Parses `/v1/access/countries`, dropping ISPs. */
        fun parse(body: String): List<Place> {
            val root = json.parseToJsonElement(body).jsonObject
            return options(root["countries"]).map { c ->
                Place(
                    code = c.str("code"), name = c.str("name"),
                    states = options(c.obj("states")?.get("options")).map { s ->
                        Place(s.str("code"), s.str("name"), cities = cities(s))
                    }.sortedBy { it.name },
                    cities = cities(c),
                )
            }.filter { it.code.isNotEmpty() }.sortedBy { it.name }
        }

        private fun cities(o: JsonObject) =
            options(o.obj("cities")?.get("options")).map { Place(it.str("code"), it.str("name")) }
                .filter { it.code.isNotEmpty() }.sortedBy { it.name }

        private fun options(e: kotlinx.serialization.json.JsonElement?): List<JsonObject> =
            (e as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

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
