package dev.resproxy

import dev.resproxy.data.Account
import dev.resproxy.data.AppSettings
import dev.resproxy.data.ConnectionSettings
import dev.resproxy.data.Location
import dev.resproxy.data.Provider
import dev.resproxy.provider.ProviderLocations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionMemoryTest {

    private val paris = Location(country = "fr", city = "paris")
    private val berlin = Location(country = "de", city = "berlin")

    @Test fun switchingBackRestoresTheSameSession() {
        var s = AppSettings(location = paris, connection = ConnectionSettings(lifetimeMinutes = 60)).withSession("aaaa1111", now = 0)
        s = s.copy(location = berlin).restoreSession(now = 1000)
        assertEquals("", s.sessionId) // never been there: a new session gets made
        s = s.withSession("bbbb2222", now = 1000)
        s = s.copy(location = paris).restoreSession(now = 2000)
        assertEquals("aaaa1111", s.sessionId)
        s = s.copy(provider = Provider.GEONODE).restoreSession(now = 2000)
        assertEquals("", s.sessionId) // same place, other provider: separate session
    }

    @Test fun expiredSessionsAreNotReused() {
        val s = AppSettings(location = paris, connection = ConnectionSettings(lifetimeMinutes = 10)).withSession("aaaa1111", now = 0)
        assertEquals("aaaa1111", s.restoreSession(now = 8 * 60_000L).sessionId)
        assertEquals("", s.restoreSession(now = 10 * 60_000L).sessionId)
        val g = AppSettings(provider = Provider.GEONODE, location = paris).withSession("g", now = 0) // 7-day setting
        assertEquals(1440, g.sessions[g.sessionKey]!!.lifetimeMinutes)
    }

    @Test fun reusingASessionKeepsItsCreationTime() {
        val s = AppSettings(location = paris, connection = ConnectionSettings(lifetimeMinutes = 10))
            .withSession("aaaa1111", now = 0).withSession("aaaa1111", now = 5 * 60_000L)
        assertEquals(0L, s.sessions[s.sessionKey]!!.createdAt)
        assertEquals("bbbb", s.withSession("bbbb", now = 1).sessions[s.sessionKey]!!.id)
    }

    @Test fun geonodeLocationsUseDashboardLoginOrProxyLogin() {
        assertEquals("me@mail.com" to "dash", ProviderLocations.geonodeAuth(Account(Provider.GEONODE, "geonode_abc", "pw", apiLogin = "me@mail.com", apiPassword = "dash")))
        assertEquals("geonode_abc" to "pw", ProviderLocations.geonodeAuth(Account(Provider.GEONODE, "geonode_abc-type-residential-country-us", "pw")))
        assertNull(ProviderLocations.geonodeAuth(Account(Provider.GEONODE)))
    }
}
