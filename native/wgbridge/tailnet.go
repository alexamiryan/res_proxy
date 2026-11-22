package main

/*
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>

static void rp_ts_log(const char* s) { __android_log_write(ANDROID_LOG_DEBUG, "ResProxyTS", s); }
*/
import "C"

import (
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"unsafe"

	"resproxy/wgbridge/internal/tailnet"
)

// Tailnet node (JNI surface of dev.resproxy.net.Tailnet). Its own state and lock: the WireGuard chain's start/stop
// (repairs, rebuilds) never touches it.
var (
	tsMu   sync.Mutex
	tsNode *tailnet.Node
)

func tsLog(format string, args ...any) {
	c := C.CString(fmt.Sprintf(format, args...))
	C.rp_ts_log(c)
	C.free(unsafe.Pointer(c))
}

func parsePorts(csv string) []int {
	var out []int
	for _, p := range strings.Split(csv, ",") {
		if n, err := strconv.Atoi(strings.TrimSpace(p)); err == nil && n > 0 && n < 65536 {
			out = append(out, n)
		}
	}
	return out
}

//export Java_dev_resproxy_net_Tailnet_nativeStart
func Java_dev_resproxy_net_Tailnet_nativeStart(env *C.JNIEnv, cls C.jclass, dir, hostname, authKey, controlURL, stateKeyHex, localAddr, loopbackPorts C.jstring) C.jstring {
	cfg := tailnet.Config{
		Dir:           goStr(env, dir),
		Hostname:      goStr(env, hostname),
		AuthKey:       goStr(env, authKey),
		ControlURL:    goStr(env, controlURL),
		LocalAddr:     goStr(env, localAddr),
		LoopbackPorts: parsePorts(goStr(env, loopbackPorts)),
		Logf:          tsLog,
	}
	key, err := hex.DecodeString(goStr(env, stateKeyHex))
	if err != nil || len(key) != 32 {
		return jStr(env, "invalid state key")
	}
	cfg.StateKey = key
	tsMu.Lock()
	defer tsMu.Unlock()
	if tsNode != nil {
		tsNode.Close()
		tsNode = nil
	}
	n, err := tailnet.Start(cfg)
	if err != nil {
		return jStr(env, err.Error())
	}
	tsNode = n
	return jStr(env, "")
}

//export Java_dev_resproxy_net_Tailnet_nativeStop
func Java_dev_resproxy_net_Tailnet_nativeStop(env *C.JNIEnv, cls C.jclass) {
	tsMu.Lock()
	defer tsMu.Unlock()
	if tsNode != nil {
		tsNode.Close()
		tsNode = nil
	}
}

//export Java_dev_resproxy_net_Tailnet_nativeLogout
func Java_dev_resproxy_net_Tailnet_nativeLogout(env *C.JNIEnv, cls C.jclass) C.jstring {
	tsMu.Lock()
	defer tsMu.Unlock()
	if tsNode == nil {
		return jStr(env, "")
	}
	err := tsNode.Logout()
	tsNode = nil
	if err != nil {
		return jStr(env, err.Error())
	}
	return jStr(env, "")
}

func node() *tailnet.Node {
	tsMu.Lock()
	defer tsMu.Unlock()
	return tsNode
}

//export Java_dev_resproxy_net_Tailnet_nativeStatus
func Java_dev_resproxy_net_Tailnet_nativeStatus(env *C.JNIEnv, cls C.jclass) C.jstring {
	n := node()
	if n == nil {
		return jStr(env, "")
	}
	b, _ := json.Marshal(n.Status())
	return jStr(env, string(b))
}

//export Java_dev_resproxy_net_Tailnet_nativeIsTailnetHost
func Java_dev_resproxy_net_Tailnet_nativeIsTailnetHost(env *C.JNIEnv, cls C.jclass, host C.jstring) C.jboolean {
	n := node()
	if n == nil {
		return C.JNI_FALSE
	}
	if n.IsTailnetHost(goStr(env, host)) {
		return C.JNI_TRUE
	}
	return C.JNI_FALSE
}

// nativeSocks returns "port:password" of the outbound SOCKS5 front, or "" when the node isn't running.
//
//export Java_dev_resproxy_net_Tailnet_nativeSocks
func Java_dev_resproxy_net_Tailnet_nativeSocks(env *C.JNIEnv, cls C.jclass) C.jstring {
	n := node()
	if n == nil {
		return jStr(env, "")
	}
	port, pass := n.Socks()
	return jStr(env, fmt.Sprintf("%d:%s", port, pass))
}

//export Java_dev_resproxy_net_Tailnet_nativeSetInterfaces
func Java_dev_resproxy_net_Tailnet_nativeSetInterfaces(env *C.JNIEnv, cls C.jclass, spec C.jstring) {
	tailnet.SetInterfaces(goStr(env, spec))
}

//export Java_dev_resproxy_net_Tailnet_nativeNetworkChanged
func Java_dev_resproxy_net_Tailnet_nativeNetworkChanged(env *C.JNIEnv, cls C.jclass) {
	if n := node(); n != nil {
		n.NetworkChanged()
	}
}
