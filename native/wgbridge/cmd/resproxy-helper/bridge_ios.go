//go:build ios

package main

// iOS build: the same helper, linked into the ResProxy packet tunnel extension as a C archive
// (`GOOS=ios go build -buildmode=c-archive`, see ios/scripts/build-native.sh). The extension calls ResProxyCall
// with the same JSON requests the Chrome extension sends over native messaging, and gets the same replies.

/*
#include <stdlib.h>
*/
import "C"

import (
	"encoding/json"
	"log"
	"runtime/debug"
	"unsafe"
)

var iosServer = &server{state: newState()}

func init() {
	// Network extensions are killed above ~50 MB: keep the Go heap well below that.
	debug.SetMemoryLimit(24 << 20)
	debug.SetGCPercent(50)
	log.SetFlags(0)
	log.SetPrefix("bridge: ")
	// The iOS tunnel opens the gate only after the reputation check.
	iosServer.state.gate = newGate(false)
}

// ResProxyCall runs one helper command (JSON {cmd, ...}) and returns the reply JSON ({ok, ...} or
// {ok:false, error, code}). The caller frees the result with ResProxyFree. Blocking; call it off the main thread.
//
//export ResProxyCall
func ResProxyCall(in *C.char) *C.char {
	buf := []byte(C.GoString(in))
	var req request
	var out map[string]any
	if err := json.Unmarshal(buf, &req); err != nil {
		out = map[string]any{"ok": false, "error": "bad request: " + err.Error(), "code": ""}
	} else {
		req.Raw = buf
		out = iosServer.call(req)
	}
	b, err := json.Marshal(out)
	if err != nil {
		b = []byte(`{"ok":false,"error":"encode failed"}`)
	}
	return C.CString(string(b))
}

//export ResProxyFree
func ResProxyFree(p *C.char) { C.free(unsafe.Pointer(p)) }
