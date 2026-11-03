package dev.resproxy

import dev.resproxy.net.WireGuardConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WireGuardConfigTest {

    // Shape of a Proton VPN export (keys are random test values).
    private val conf = """
        [Interface]
        # Key for test
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.2.0.2/32
        DNS = 10.2.0.1

        [Peer]
        # NL#123
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 185.107.56.1:51820
    """.trimIndent()

    @Test fun parsesProtonStyleConfig() {
        val c = WireGuardConfig.parse(conf)
        assertEquals(listOf("10.2.0.2/32"), c.addresses)
        assertEquals(listOf("10.2.0.1"), c.dns)
        assertEquals("185.107.56.1:51820", c.endpoint)
        val uapi = c.toUapi { it }
        assertTrue(uapi.startsWith("private_key=c809f3e5317e95"))
        assertTrue(uapi.contains("endpoint=185.107.56.1:51820"))
        assertTrue(uapi.contains("allowed_ip=0.0.0.0/0\nallowed_ip=::/0"))
        assertTrue(uapi.contains("persistent_keepalive_interval=25"))
    }

    @Test fun resolvesHostnameEndpoints() {
        val c = WireGuardConfig.parse(conf.replace("185.107.56.1:51820", "nl-free.example.net:51820"))
        assertTrue(c.toUapi { "203.0.113.9" }.contains("endpoint=203.0.113.9:51820"))
        assertTrue(c.toUapi { "2001:db8::1" }.contains("endpoint=[2001:db8::1]:51820"))
    }

    @Test(expected = IOException::class) fun rejectsMissingKey() {
        WireGuardConfig.parse(conf.lines().filterNot { it.startsWith("PrivateKey") }.joinToString("\n"))
    }

    @Test(expected = IOException::class) fun rejectsBadKey() {
        WireGuardConfig.parse(conf.replace("yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=", "short"))
    }
}
