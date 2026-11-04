package dev.resproxy

import dev.resproxy.data.MullvadDevice
import dev.resproxy.data.VpnExit
import dev.resproxy.net.MullvadClient
import dev.resproxy.net.WireGuardConfig
import dev.resproxy.net.X25519
import dev.resproxy.vpn.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress
import java.util.Base64

class VpnFeaturesTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun x25519MatchesRfc7748() {
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hex(X25519.publicKey(alice)))
        val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPub = X25519.publicKey(bob)
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", hex(X25519.scalarMult(alice, bobPub)))
        val (priv, pub) = X25519.newKeyPair()
        assertEquals(hex(X25519.publicKey(Base64.getDecoder().decode(priv))), hex(Base64.getDecoder().decode(pub)))
    }

    private fun contains(routes: List<Routes.Route>, ip: String): Boolean {
        val a = InetAddress.getByName(ip).address
        val bits = a.size * 8
        val v = BigInteger(1, a)
        return routes.any { r ->
            val base = BigInteger(1, InetAddress.getByName(r.address).address)
            v.shiftRight(bits - r.prefix) == base.shiftRight(bits - r.prefix)
        }
    }

    @Test fun lanBypassRoutesExcludePrivateRanges() {
        val v4 = Routes.v4(bypassLan = true)
        for (ip in listOf("192.168.1.1", "10.20.30.40", "172.16.5.5", "172.31.255.255", "169.254.1.1", "224.0.0.251")) {
            assertFalse(ip, contains(v4, ip))
        }
        for (ip in listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "100.64.0.5", "198.18.0.2", "223.255.255.255", "11.0.0.1")) {
            assertTrue(ip, contains(v4, ip))
        }
        val v6 = Routes.v6(bypassLan = true)
        assertFalse(contains(v6, "fe80::1")); assertFalse(contains(v6, "fd12::1"))
        assertTrue(contains(v6, "2a00:1450::1"))
        assertEquals(listOf(Routes.Route("0.0.0.0", 0)), Routes.v4(false))
    }

    private val relaysJson = """
        {"locations":{"se-got":{"country":"Sweden","city":"Gothenburg"},"se-sto":{"country":"Sweden","city":"Stockholm"},
          "de-fra":{"country":"Germany","city":"Frankfurt"}},
         "wireguard":{"relays":[
          {"hostname":"se-got-wg-001","location":"se-got","active":true,"owned":true,"ipv4_addr_in":"185.213.154.68","public_key":"${"A".repeat(43)}=","weight":100},
          {"hostname":"se-sto-wg-002","location":"se-sto","active":true,"ipv4_addr_in":"1.2.3.4","public_key":"${"B".repeat(43)}="},
          {"hostname":"de-fra-wg-009","location":"de-fra","active":false,"ipv4_addr_in":"5.6.7.8","public_key":"${"C".repeat(43)}="}
         ]}}
    """.trimIndent()

    @Test fun mullvadRelaysParseAndPick() {
        val relays = MullvadClient.parseRelays(relaysJson)
        assertEquals(listOf("se-got-wg-001", "se-sto-wg-002"), relays.map { it.hostname }) // inactive dropped
        assertEquals("Gothenburg", relays[0].city)
        assertEquals("se-got-wg-001", MullvadClient.pick("se-got-wg-001", relays)?.hostname)
        assertEquals("se-sto-wg-002", MullvadClient.pick("se-sto", relays)?.hostname)
        assertTrue(MullvadClient.pick("se", relays)!!.countryCode == "se")
        assertNull(MullvadClient.pick("de", relays))
        assertEquals("Sweden, Stockholm", MullvadClient.label("se-sto", relays))
        assertEquals("Sweden", MullvadClient.label("se", relays))
    }

    @Test fun mullvadConfigIsValidWireGuard() {
        val (priv, pub) = X25519.newKeyPair()
        val device = MullvadDevice("id", "witty owl", priv, pub, "10.66.1.2/32", "fc00:bbbb:bbbb:bb01::3:1/128")
        val relay = MullvadClient.parseRelays(relaysJson)[0]
        val cfg = WireGuardConfig.parse(MullvadClient.wgQuick(device, relay))
        assertEquals("185.213.154.68:51820", cfg.endpoint)
        assertEquals(listOf("10.64.0.1"), cfg.dns)
        assertEquals(2, cfg.addresses.size)
    }

    @Test fun pingOutputParses() {
        val out = "PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.\n64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=23.6 ms\n"
        assertEquals(24L, dev.resproxy.net.LatencyProbe.parse(out))
        assertNull(dev.resproxy.net.LatencyProbe.parse("1 packets transmitted, 0 received, 100% packet loss"))
    }

    @Test fun vpnExitRoundTrip() {
        assertEquals(VpnExit.Imported("ab12"), VpnExit.parse(VpnExit.imported("ab12")))
        assertEquals(VpnExit.Mullvad("se-got"), VpnExit.parse(VpnExit.mullvad("se-got")))
        assertNull(VpnExit.parse(""))
        assertEquals("1234567890123456", MullvadClient.normalize("1234 5678 9012 3456"))
    }
}
