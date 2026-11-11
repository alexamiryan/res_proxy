package dev.resproxy.vpn

/**
 * JNI surface of libhev-socks5-tunnel.so. Method names are fixed by hev-jni.c, which registers
 * them on `dev/resproxy/vpn/TunnelNative` (see PKGNAME/CLSNAME in jni/Application.mk).
 */
@Suppress("FunctionName")
object TunnelNative {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    /** Starts the tunnel on a worker thread. Does not take ownership of [tunFd]. */
    external fun TProxyStartService(configPath: String, tunFd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
    /**
     * [txPackets, txBytes, rxPackets, rxBytes] from hev's side: tx = read from the TUN (apps' upload),
     * rx = written to the TUN (apps' download). See hev-socks5-tunnel.c (`stat_tx_*` in the TUN read loop).
     */
    external fun TProxyGetStats(): LongArray
}
