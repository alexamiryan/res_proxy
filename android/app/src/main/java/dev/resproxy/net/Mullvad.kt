package dev.resproxy.net

import dev.resproxy.data.MullvadAccount
import dev.resproxy.data.MullvadDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class MullvadRelay(
    val hostname: String,
    /** `se-got` */
    val location: String,
    val country: String,
    val city: String,
    val ipv4: String,
    val publicKey: String,
    val owned: Boolean = false,
    val weight: Int = 100,
) {
    val countryCode get() = location.substringBefore('-')
}

/** A device (WireGuard key) registered on the account, as listed by Mullvad. */
data class MullvadDeviceInfo(val id: String, val name: String, val created: String)

@Serializable
private data class RelayCache(val fetchedAt: Long, val relays: List<MullvadRelay>)

/**
 * Mullvad's public API: account login by account number, registering this install's WireGuard key
 * as a device, and the WireGuard server list. Every Mullvad account allows 5 devices.
 */
class MullvadClient(private val cacheFile: File) {

    private val http = OkHttpClient.Builder().dns(Resolver).callTimeout(30, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()

    private val _relays = MutableStateFlow(loadCache()?.relays.orEmpty())
    val relays: StateFlow<List<MullvadRelay>> = _relays.asStateFlow()
    private var fetchedAt = loadCache()?.fetchedAt ?: 0L

    suspend fun refreshRelays(force: Boolean = false): List<MullvadRelay> = withContext(Dispatchers.IO) {
        if (!force && _relays.value.isNotEmpty() && System.currentTimeMillis() - fetchedAt < 6 * 3600_000L) return@withContext _relays.value
        val body = call(Request.Builder().url("$API/app/v1/relays").build())
        val list = parseRelays(body)
        if (list.isEmpty()) throw IOException("Mullvad returned no WireGuard servers")
        fetchedAt = System.currentTimeMillis()
        runCatching { cacheFile.writeText(json.encodeToString(RelayCache.serializer(), RelayCache(fetchedAt, list))) }
        _relays.value = list
        list
    }

    /** Logs in and returns the account with its expiry; also checks this install's device still exists. */
    suspend fun login(number: String, existing: MullvadDevice?): MullvadAccount = withContext(Dispatchers.IO) {
        val n = normalize(number)
        val token = token(n)
        val expiry = runCatching {
            json.parseToJsonElement(call(authed("$API/accounts/v1/accounts/me", token).build())).jsonObject.str("expiry")
        }.getOrNull().orEmpty()
        val device = existing?.takeIf { deviceExists(token, it.id) } ?: registerDevice(token)
        MullvadAccount(n, expiry, device)
    }

    /** Whether [deviceId] is still on the account (false once it was removed on mullvad.net or by another install). */
    suspend fun isDeviceRegistered(number: String, deviceId: String): Boolean = withContext(Dispatchers.IO) {
        deviceExists(token(normalize(number)), deviceId)
    }

    /** Every device on the account (each app install / Android profile / browser registers its own). */
    suspend fun devices(number: String): List<MullvadDeviceInfo> = withContext(Dispatchers.IO) {
        val token = token(normalize(number))
        parseDevices(call(authed("$API/accounts/v1/devices", token).build()))
    }

    suspend fun removeDevice(number: String, deviceId: String) = withContext(Dispatchers.IO) {
        val token = token(normalize(number))
        call(authed("$API/accounts/v1/devices/$deviceId", token).delete().build(), allow404 = true)
        Unit
    }

    private fun token(number: String): String {
        val body = buildJsonObject { put("account_number", number) }.toString().toRequestBody(jsonType)
        val r = call(Request.Builder().url("$API/auth/v1/token").post(body).build())
        return json.parseToJsonElement(r).jsonObject.str("access_token") ?: throw IOException("Mullvad login failed")
    }

    private fun deviceExists(token: String, id: String): Boolean {
        if (id.isEmpty()) return false
        return call(authed("$API/accounts/v1/devices/$id", token).build(), allow404 = true).isNotEmpty()
    }

    private fun registerDevice(token: String): MullvadDevice {
        val (priv, pub) = X25519.newKeyPair()
        val body = buildJsonObject { put("pubkey", pub); put("hijack_dns", false) }.toString().toRequestBody(jsonType)
        val o = json.parseToJsonElement(call(authed("$API/accounts/v1/devices", token).post(body).build())).jsonObject
        return MullvadDevice(
            id = o.str("id") ?: throw IOException("Mullvad didn't return a device"),
            name = o.str("name").orEmpty(),
            privateKey = priv,
            publicKey = pub,
            ipv4 = o.str("ipv4_address").orEmpty(),
            ipv6 = o.str("ipv6_address").orEmpty(),
        )
    }

    private fun authed(url: String, token: String) = Request.Builder().url(url).header("Authorization", "Bearer $token")

    /** Body text; "" for an allowed 404. Mullvad errors come as `{"code": "...", "error": "..."}`. */
    private fun call(req: Request, allow404: Boolean = false): String = http.newCall(req).execute().use { r ->
        val text = r.body?.string().orEmpty()
        if (r.isSuccessful) return text
        if (allow404 && r.code == 404) return ""
        val code = runCatching { json.parseToJsonElement(text).jsonObject.str("code") }.getOrNull()
        throw IOException(
            when (code) {
                "INVALID_ACCOUNT", "INVALID_ACCOUNT_NUMBER" -> "Mullvad doesn't recognise this account number"
                "MAX_DEVICES_REACHED" -> "This Mullvad account already has 5 devices. Remove one at mullvad.net → Devices, then try again."
                "ACCOUNT_EXPIRED" -> "This Mullvad account has no time left"
                else -> "Mullvad API error ${r.code}${code?.let { " ($it)" } ?: ""}"
            }
        )
    }

    private fun loadCache(): RelayCache? =
        runCatching { json.decodeFromString(RelayCache.serializer(), cacheFile.readText()) }.getOrNull()

    companion object {
        private const val API = "https://api.mullvad.net"
        const val DNS = "10.64.0.1"
        private val json = Json { ignoreUnknownKeys = true }

        fun normalize(number: String) = number.filter(Char::isDigit)

        internal fun parseRelays(body: String): List<MullvadRelay> {
            val root = json.parseToJsonElement(body).jsonObject
            val locations = root["locations"] as? JsonObject ?: JsonObject(emptyMap())
            val wg = root["wireguard"] as? JsonObject ?: return emptyList()
            val relays = wg["relays"] as? JsonArray ?: return emptyList()
            return relays.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                if ((o["active"] as? JsonPrimitive)?.booleanOrNull == false) return@mapNotNull null
                val loc = o.str("location") ?: return@mapNotNull null
                val place = locations[loc] as? JsonObject
                MullvadRelay(
                    hostname = o.str("hostname") ?: return@mapNotNull null,
                    location = loc,
                    country = place?.str("country") ?: loc.substringBefore('-').uppercase(),
                    city = place?.str("city") ?: loc,
                    ipv4 = o.str("ipv4_addr_in") ?: return@mapNotNull null,
                    publicKey = o.str("public_key") ?: return@mapNotNull null,
                    owned = (o["owned"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    weight = (o["weight"] as? JsonPrimitive)?.intOrNull ?: 100,
                )
            }
        }

        internal fun parseDevices(body: String): List<MullvadDeviceInfo> =
            (json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                MullvadDeviceInfo(o.str("id") ?: return@mapNotNull null, o.str("name").orEmpty(), o.str("created").orEmpty())
            }.sortedBy { it.created }

        /** Relays [code] can mean (a hostname, a `country-city` location, or a country code), in a stable order. */
        fun candidates(code: String, relays: List<MullvadRelay>): List<MullvadRelay> {
            relays.firstOrNull { it.hostname == code }?.let { return listOf(it) }
            return relays.filter { it.location == code }.ifEmpty { relays.filter { it.countryCode == code } }.sortedBy { it.hostname }
        }

        /**
         * Picks by weight, or with a [slot] always the same relay for the same slot. Installs sharing one Mullvad
         * device need different relays: a WireGuard server talks to one endpoint per key, so two tunnels with the same
         * key on one server keep taking the session from each other.
         */
        fun pick(code: String, relays: List<MullvadRelay>, slot: Int? = null): MullvadRelay? {
            val matches = candidates(code, relays)
            if (matches.isEmpty()) return null
            if (slot != null) return matches[Math.floorMod(slot, matches.size)]
            val total = matches.sumOf { it.weight.coerceAtLeast(1) }
            var r = (Math.random() * total).toInt()
            for (m in matches) { r -= m.weight.coerceAtLeast(1); if (r < 0) return m }
            return matches.last()
        }

        /** Human label for an exit code, e.g. "Sweden", "Sweden, Gothenburg", "se-got-wg-001". */
        fun label(code: String, relays: List<MullvadRelay>): String {
            relays.firstOrNull { it.hostname == code }?.let { return "${it.city} · ${it.hostname}" }
            relays.firstOrNull { it.location == code }?.let { return "${it.country}, ${it.city}" }
            relays.firstOrNull { it.countryCode == code }?.let { return it.country }
            return code
        }

        fun wgQuick(device: MullvadDevice, relay: MullvadRelay): String = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${device.privateKey}")
            appendLine("Address = ${listOf(device.ipv4, device.ipv6).filter { it.isNotBlank() }.joinToString(", ")}")
            appendLine("DNS = $DNS")
            appendLine("[Peer]")
            appendLine("PublicKey = ${relay.publicKey}")
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("Endpoint = ${relay.ipv4}:51820")
        }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    }
}
