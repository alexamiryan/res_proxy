package dev.resproxy

import dev.resproxy.data.Account
import dev.resproxy.data.AppSettings
import dev.resproxy.data.Location
import dev.resproxy.data.Provider
import dev.resproxy.provider.ProviderLocations
import dev.resproxy.provider.Presets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationsTest {

    // Shape of IPRoyal's GET /v1/access/countries (from their API docs), trimmed.
    private val body = """
        {"prefix":"_country-","countries":[
          {"code":"us","name":"United States",
           "cities":{"prefix":"_city-","options":[{"code":"newyork","name":"New York","isps":{"prefix":"_isp-","options":[]}}]},
           "states":{"prefix":"_state-","options":[
             {"code":"iowa","name":"Iowa","cities":{"prefix":"_city-","options":[{"code":"desmoines","name":"Des Moines"}]},"isps":{"options":[]}},
             {"code":"california","name":"California","cities":{"options":[{"code":"losangeles","name":"Los Angeles"}]}}]}},
          {"code":"am","name":"Armenia",
           "cities":{"prefix":"_city-","options":[{"code":"armavir","name":"Armavir","isps":{"prefix":"_isp-","options":[{"code":"x","name":"X"}]}}]},
           "states":{"prefix":"_state-","options":[]}}
        ]}
    """.trimIndent()

    @Test fun parsesCountriesStatesCities() {
        val list = ProviderLocations.parseIpRoyal(body)
        assertEquals(listOf("am", "us"), list.map { it.code })
        val us = list.first { it.code == "us" }
        assertEquals(listOf("California", "Iowa"), us.states.map { it.name })
        assertEquals("desmoines", us.states.first { it.code == "iowa" }.cities.single().code)
        assertEquals("newyork", us.cities.single().code)
        assertTrue(list.first { it.code == "am" }.states.isEmpty())
    }

    // Shape of Geonode's GET monitor.geonode.com/services/RESIDENTIAL-PREMIUM/targeting-options.
    private val geonode = """
        {"countries":[
          {"code":"de","name":"Germany","asns":[{"code":"3320","name":"Deutsche Telekom AG"}],
           "states":[{"code":"badenwurttemberg","name":"Baden-Württemberg","cities":[{"code":"aalen","name":"Aalen"},{"code":"stuttgart","name":"Stuttgart"}]},
                     {"code":"bavaria","name":"Bavaria","cities":[{"code":"munich","name":"Munich"}]}]},
          {"code":"US","name":"United States","asns":[],"states":[{"code":"alabama","name":"Alabama","cities":[{"code":"birmingham","name":"Birmingham"}]}]}
        ]}
    """.trimIndent()

    @Test fun parsesGeonodeTargetingOptions() {
        val list = ProviderLocations.parseGeonode(geonode)
        assertEquals(listOf("de", "us"), list.map { it.code })
        val de = list.first()
        assertEquals(listOf("badenwurttemberg", "bavaria"), de.states.map { it.code })
        assertEquals(listOf("Aalen", "Munich", "Stuttgart"), de.cities.map { it.name })
    }

    @Test fun geonodeAccountBuildsUsernameAndGateway() {
        val s = AppSettings(
            provider = Provider.GEONODE,
            location = Location(country = "de", state = "badenwurttemberg", city = "aalen"),
            connection = dev.resproxy.data.ConnectionSettings(
                gateways = mapOf(Provider.GEONODE to dev.resproxy.data.Gateway("us.proxy.geonode.io")),
            ),
        )
        val ep = Presets.build(dev.resproxy.data.Profile.from(Account(Provider.GEONODE, "geonode_abc", "pw"), s, "ab12cd34"))
        assertEquals("us.proxy.geonode.io", ep.host)
        assertEquals(12000, ep.port)
        assertEquals("geonode_abc-type-residential-country-de-state-badenwurttemberg-city-aalen-session-ab12cd34-lifetime-1440", ep.username)
        assertEquals("pw", ep.password)
        // IPRoyal's gateway override doesn't leak into Geonode and vice versa.
        val ip = Presets.build(dev.resproxy.data.Profile.from(Account(Provider.IPROYAL, "u", "p"), s.copy(provider = Provider.IPROYAL), "x"))
        assertEquals("geo.iproyal.com", ip.host)
    }

    @Test fun builtInHasCountriesAndUsStates() {
        val c = ProviderLocations.builtIn()
        assertTrue(c.countries.size > 200)
        val us = c.country("us")!!
        assertEquals("United States", us.name)
        assertTrue(us.states.any { it.code == "newyork" && it.name == "New York" })
    }

    @Test fun accountAndLocationBuildIpRoyalPassword() {
        val s = AppSettings(location = Location(country = "us", countryName = "United States", state = "iowa", stateName = "Iowa",
            city = "desmoines", cityName = "Des Moines"))
        val p = dev.resproxy.data.Profile.from(Account(Provider.IPROYAL, "user1", "secret"), s, "ab12cd34")
        val ep = Presets.build(p)
        assertEquals("geo.iproyal.com", ep.host)
        assertEquals(32325, ep.port)
        assertEquals("secret_country-us_state-iowa_city-desmoines_session-ab12cd34_lifetime-1d", ep.password)
        assertEquals("Des Moines, Iowa, United States", s.location.label)
    }
}

class ResolverTest {
    @Test fun parsesDohAnswersAndDropsSinkholes() {
        val body = """{"Status":0,"Answer":[{"name":"geo.iproyal.com","type":5,"data":"x.iproyal.com."},
            {"name":"x","type":1,"TTL":60,"data":"185.230.245.187"},{"name":"x","type":1,"data":"0.0.0.0"}]}"""
        assertEquals(listOf("185.230.245.187"), dev.resproxy.net.Resolver.parse(body).map { it.hostAddress })
        assertEquals("127.0.0.1", dev.resproxy.net.Resolver.resolve("127.0.0.1").hostAddress)
    }
}

class ProxyLineTest {
    @Test fun parsesIpRoyalProxyLines() {
        assertEquals("abc" to "pw_country-us", dev.resproxy.ui.parseProxyLine("geo.iproyal.com:12321:abc:pw_country-us"))
        assertEquals("abc" to "pw", dev.resproxy.ui.parseProxyLine("socks5://abc:pw@geo.iproyal.com:32325"))
        assertEquals(null, dev.resproxy.ui.parseProxyLine("abc"))
        assertEquals(null, dev.resproxy.ui.parseProxyLine("me@mail.com"))
    }
}
