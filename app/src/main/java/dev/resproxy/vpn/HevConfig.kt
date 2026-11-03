package dev.resproxy.vpn

/** Builds the hev-socks5-tunnel YAML config. Addresses must match what [ProxyVpnService] puts on the TUN. */
object HevConfig {
    const val TUN_V4 = "198.18.0.1"
    const val TUN_V6 = "fc00::1"
    const val MAPDNS = "198.18.0.2"
    const val MTU = 8500

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
            // Fake-IP DNS: apps get 100.64/10 addresses, hev sends the hostname to the proxy.
            appendLine("mapdns:")
            appendLine("  address: $MAPDNS")
            appendLine("  port: 53")
            appendLine("  network: 100.64.0.0")
            appendLine("  netmask: 255.192.0.0")
            appendLine("  cache-size: 10000")
            appendLine("misc:")
            appendLine("  task-stack-size: 81920")
            appendLine("  connect-timeout: 10000")
            appendLine("  log-level: $logLevel")
        }

    /** YAML single-quoted scalar. */
    internal fun q(s: String) = "'" + s.replace("'", "''") + "'"
}
