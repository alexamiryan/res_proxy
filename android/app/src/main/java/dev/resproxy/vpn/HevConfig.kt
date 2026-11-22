package dev.resproxy.vpn

/** Builds the hev-socks5-tunnel YAML config. Addresses must match what [ProxyVpnService] puts on the TUN. */
object HevConfig {
    const val TUN_V4 = "198.18.0.1"
    const val TUN_V6 = "fc00::1"
    const val MAPDNS = "198.18.0.2"
    const val MTU = 8500
    /** hev's fake-IP DNS pool. Not 100.64/10: that is Tailscale's address range (tailnet peers). */
    const val FAKE_NET = "198.19.0.0"
    const val FAKE_MASK = "255.255.0.0"

    fun build(socksHost: String, socksPort: Int, username: String?, password: String?, logLevel: String = "warn") =
        buildString {
            appendLine("tunnel:")
            appendLine("  mtu: $MTU")
            appendLine("  ipv4: $TUN_V4")
            appendLine("  ipv6: ${q(TUN_V6)}")
            appendLine("socks5:")
            appendLine("  address: ${q(socksHost)}")
            appendLine("  port: $socksPort")
            appendLine("  udp: 'udp'")
            if (!username.isNullOrEmpty()) {
                appendLine("  username: ${q(username)}")
                appendLine("  password: ${q(password.orEmpty())}")
            }
            // Fake-IP DNS: apps get 198.19/16 addresses, hev sends the hostname to the relay.
            appendLine("mapdns:")
            appendLine("  address: $MAPDNS")
            appendLine("  port: 53")
            appendLine("  network: $FAKE_NET")
            appendLine("  netmask: $FAKE_MASK")
            appendLine("  cache-size: 10000")
            appendLine("misc:")
            appendLine("  task-stack-size: 81920")
            appendLine("  connect-timeout: 10000")
            appendLine("  log-level: $logLevel")
        }

    /** YAML single-quoted scalar. */
    internal fun q(s: String) = "'" + s.replace("'", "''") + "'"
}
