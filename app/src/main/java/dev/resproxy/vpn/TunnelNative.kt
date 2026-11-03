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
    /** [txPackets, txBytes, rxPackets, rxBytes]; tx = written to TUN (download), rx = read from TUN (upload). */
    external fun TProxyGetStats(): LongArray
}
