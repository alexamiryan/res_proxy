package dev.resproxy

import dev.resproxy.data.Profile
import dev.resproxy.data.Protocol
import dev.resproxy.data.Provider
import dev.resproxy.provider.GeonodePreset
import dev.resproxy.provider.IpRoyalPreset
import dev.resproxy.provider.Presets
import dev.resproxy.vpn.HevConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsTest {

    private val base = Profile(username = "user1", password = "secret", sessionId = "ab12cd34")

    @Test fun iproyalStickyWithTargeting() {
        val ep = Presets.build(base.copy(provider = Provider.IPROYAL, country = "US", state = "California", city = "Los Angeles"))
        assertEquals("geo.iproyal.com", ep.host)
        assertEquals(32325, ep.port)
        assertEquals("user1", ep.username)
        assertEquals("secret_country-us_state-california_city-losangeles_session-ab12cd34_lifetime-30m", ep.password)
    }

    @Test fun iproyalRotatingHttpStripsPastedSuffix() {
        val ep = Presets.build(
            base.copy(provider = Provider.IPROYAL, protocol = Protocol.HTTP, sticky = false, password = "secret_country-de_session-old")
        )
        assertEquals(12321, ep.port)
        assertEquals("secret", ep.password)
    }

    @Test fun iproyalLifetimeUnits() {
        assertEquals("45m", IpRoyalPreset.lifetime(45))
        assertEquals("2h", IpRoyalPreset.lifetime(120))
        assertEquals("1d", IpRoyalPreset.lifetime(1440))
        assertEquals("7d", IpRoyalPreset.lifetime(999_999))
    }

    @Test fun geonodeStickyAndPorts() {
        val p = base.copy(provider = Provider.GEONODE, username = "geonode_abc", country = "gb", lifetimeMinutes = 10)
        val ep = Presets.build(p)
        assertEquals("proxy.geonode.io", ep.host)
        assertEquals(12000, ep.port)
        assertEquals("geonode_abc-type-residential-country-gb-session-ab12cd34-lifetime-10", ep.username)
        assertEquals("secret", ep.password)
        assertEquals(9000, GeonodePreset.defaultPort(Protocol.HTTP, sticky = false))
        assertEquals(11000, GeonodePreset.defaultPort(Protocol.SOCKS5, sticky = false))
    }

    @Test fun geonodeStripsPastedParams() {
        val ep = Presets.build(base.copy(provider = Provider.GEONODE, username = "geonode_abc-type-residential-country-us", sticky = false))
        assertEquals("geonode_abc-type-residential", ep.username)
    }

    @Test fun overridesWin() {
        val ep = Presets.build(base.copy(provider = Provider.GEONODE, host = "custom.host", port = 1234))
        assertEquals("custom.host", ep.host)
        assertEquals(1234, ep.port)
    }

    @Test fun sessionIdShape() {
        repeat(50) { assertTrue(Regex("^[a-z0-9]{8}$").matches(Presets.newSessionId())) }
    }

    @Test fun hevConfigQuotesCredentials() {
        val yaml = HevConfig.build("1.2.3.4", 1080, "us'er", "p:a#ss")
        assertTrue(yaml.contains("  address: '1.2.3.4'"))
        assertTrue(yaml.contains("  username: 'us''er'"))
        assertTrue(yaml.contains("  password: 'p:a#ss'"))
        assertTrue(yaml.contains("mapdns:\n  address: ${HevConfig.MAPDNS}"))
        val anon = HevConfig.build("127.0.0.1", 5555, null, null)
        assertTrue(!anon.contains("username"))
    }
}
