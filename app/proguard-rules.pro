# JNI entry points registered by name from libhev-socks5-tunnel.so
-keep class dev.resproxy.vpn.TunnelNative { native <methods>; }
-keepclassmembers class dev.resproxy.vpn.TunnelNative { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; *; }
