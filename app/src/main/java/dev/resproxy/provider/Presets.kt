package dev.resproxy.provider

import dev.resproxy.data.Profile
import dev.resproxy.data.Protocol
import dev.resproxy.data.Provider
import java.security.SecureRandom

/** Concrete upstream credentials after the provider's targeting/session syntax has been applied. */
data class Endpoint(
    val host: String,
    val port: Int,
    val protocol: Protocol,
    val username: String,
    val password: String,
)

interface ProviderPreset {
    val defaultHost: String
    fun defaultPort(protocol: Protocol, sticky: Boolean): Int
    /** Short description of the credential syntax, shown in the profile editor. */
    val formatHint: String
    fun build(p: Profile): Endpoint

    fun hostOf(p: Profile) = p.host.trim().ifEmpty { defaultHost }
    fun portOf(p: Profile) = if (p.port > 0) p.port else defaultPort(p.protocol, p.sticky)
}

/**
 * IPRoyal residential: targeting and session are appended to the *password*, e.g.
 * `pass_country-us_state-california_city-losangeles_session-ab12cd34_lifetime-30m`.
 */
object IpRoyalPreset : ProviderPreset {
    override val defaultHost = "geo.iproyal.com"
    override fun defaultPort(protocol: Protocol, sticky: Boolean) = if (protocol == Protocol.SOCKS5) 32325 else 12321
    override val formatHint = "password_country-xx_state-…_city-…_session-<id>_lifetime-30m"

    private val paramSuffix = Regex("_(country|state|region|city|session|lifetime|streaming|skipispstatic)-.*$")

    override fun build(p: Profile): Endpoint {
        val pass = buildString {
            append(p.password.trim().replace(paramSuffix, ""))
            token(p.country)?.let { append("_country-").append(it) }
            token(p.state)?.let { append("_state-").append(it) }
            token(p.city)?.let { append("_city-").append(it) }
            if (p.sticky && p.sessionId.isNotEmpty()) {
                append("_session-").append(p.sessionId)
                append("_lifetime-").append(lifetime(p.lifetimeMinutes))
            }
        }
        return Endpoint(hostOf(p), portOf(p), p.protocol, p.username.trim(), pass)
    }

    internal fun lifetime(minutes: Int): String {
        val m = minutes.coerceIn(1, 7 * 1440)
        return when {
            m % 1440 == 0 -> "${m / 1440}d"
            m % 60 == 0 -> "${m / 60}h"
            else -> "${m}m"
        }
    }
}

/**
 * Geonode residential: targeting and session are appended to the *username*, e.g.
 * `geonode_abc123-type-residential-country-us-city-london-session-ab12cd34-lifetime-30`.
 * Separate gateway ports for rotating vs sticky and for HTTP vs SOCKS5.
 */
object GeonodePreset : ProviderPreset {
    override val defaultHost = "proxy.geonode.io"
    override fun defaultPort(protocol: Protocol, sticky: Boolean) = when (protocol) {
        Protocol.HTTP -> if (sticky) 10000 else 9000
        Protocol.SOCKS5 -> if (sticky) 12000 else 11000
    }
    override val formatHint = "username-type-residential-country-xx-state-…-city-…-session-<id>-lifetime-<min>"

    private val paramSuffix = Regex("-(type|country|state|city|asn|session|lifetime)-.*$")

    override fun build(p: Profile): Endpoint {
        val user = buildString {
            append(p.username.trim().replace(paramSuffix, ""))
            append("-type-residential")
            token(p.country)?.let { append("-country-").append(it) }
            token(p.state)?.let { append("-state-").append(it) }
            token(p.city)?.let { append("-city-").append(it) }
            if (p.sticky && p.sessionId.isNotEmpty()) {
                append("-session-").append(p.sessionId)
                append("-lifetime-").append(p.lifetimeMinutes.coerceIn(1, 1440))
            }
        }
        return Endpoint(hostOf(p), portOf(p), p.protocol, user, p.password.trim())
    }
}

/** Any SOCKS5/HTTP proxy; credentials are used verbatim. */
object GenericPreset : ProviderPreset {
    override val defaultHost = ""
    override fun defaultPort(protocol: Protocol, sticky: Boolean) = if (protocol == Protocol.SOCKS5) 1080 else 8080
    override val formatHint = "Credentials are sent exactly as entered"
    override fun build(p: Profile) =
        Endpoint(hostOf(p), portOf(p), p.protocol, p.username.trim(), p.password)
}

object Presets {
    fun of(provider: Provider): ProviderPreset = when (provider) {
        Provider.IPROYAL -> IpRoyalPreset
        Provider.GEONODE -> GeonodePreset
        Provider.GENERIC -> GenericPreset
    }

    fun build(p: Profile): Endpoint = of(p.provider).build(p)

    private val rng = SecureRandom()
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** 8 lowercase alphanumerics: accepted by both IPRoyal and Geonode session syntax. */
    fun newSessionId(): String = String(CharArray(8) { ALPHABET[rng.nextInt(ALPHABET.length)] })
}

/** Normalizes a targeting value ("New York" -> "newyork"); null when unset. */
private fun token(value: String): String? =
    value.trim().lowercase().replace(Regex("[^a-z0-9]"), "").ifEmpty { null }
