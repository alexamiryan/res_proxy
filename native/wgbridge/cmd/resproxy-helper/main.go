// resproxy-helper is the native-messaging host for the ResProxy Chrome extension. Chrome starts it on demand;
// it runs a local HTTP proxy (127.0.0.1, random port, token auth) that reaches the residential proxy through
// SOCKS5 or HTTP CONNECT, optionally inside a userspace WireGuard tunnel (wireguard-go + gVisor netstack), so
// the extension gets VPN chaining, SOCKS5 with a password and exact byte counts.
//
//	resproxy-helper install [--extension-id ID]   register with Chrome, Edge, Brave, Chromium (current user)
//	resproxy-helper uninstall
//	resproxy-helper version
//
// Protocol (stdin/stdout, Chrome native messaging: uint32 length + JSON): requests {id, cmd, ...},
// replies {id, ok, ...} or {id, ok:false, error, code}. stdout carries only messages; logs go to stderr.
package main

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
)

// Version is set at build time (-ldflags "-X main.Version=…").
var Version = "dev"

const hostName = "dev.resproxy.helper"

// defaultExtensionID is the ID fixed by chrome/extension-key.pub.
const defaultExtensionID = "hncfepeimcljgcafphgmcjhigjcfdemc"

func main() {
	log.SetOutput(os.Stderr)
	log.SetPrefix("resproxy-helper: ")
	args := os.Args[1:]
	if len(args) > 0 {
		switch args[0] {
		case "install":
			id := defaultExtensionID
			for i := 1; i < len(args)-1; i++ {
				if args[i] == "--extension-id" {
					id = args[i+1]
				}
			}
			if err := install(id); err != nil {
				fmt.Fprintln(os.Stderr, "install failed:", err)
				os.Exit(1)
			}
			return
		case "uninstall":
			if err := uninstall(); err != nil {
				fmt.Fprintln(os.Stderr, "uninstall failed:", err)
				os.Exit(1)
			}
			fmt.Println("Removed the ResProxy helper registration.")
			return
		case "version", "--version", "-v":
			fmt.Println(Version)
			return
		}
		// Chrome passes the caller's origin (and on Windows a parent window handle); anything else is a mistake.
		if !strings.HasPrefix(args[0], "chrome-extension://") && !strings.HasPrefix(args[0], "--parent-window") {
			fmt.Fprintln(os.Stderr, "Usage: resproxy-helper install | uninstall | version")
			os.Exit(2)
		}
	} else if isTerminal() {
		fmt.Println("ResProxy helper " + Version)
		fmt.Println("Run `resproxy-helper install` once; Chrome starts the helper by itself after that.")
		return
	}
	startLog(args)
	serve(os.Stdin, os.Stdout)
	log.Printf("stdin closed, exiting")
}

// startLog mirrors log output to a small file next to the user's app data, so "is the browser starting the
// helper at all?" can be answered: %LOCALAPPDATA%\ResProxy\helper.log (or ~/.cache/resproxy/helper.log).
func startLog(args []string) {
	dir, err := os.UserCacheDir()
	if err != nil {
		return
	}
	dir = filepath.Join(dir, "ResProxy")
	if os.MkdirAll(dir, 0o755) != nil {
		return
	}
	path := filepath.Join(dir, "helper.log")
	if fi, err := os.Stat(path); err == nil && fi.Size() > 256<<10 {
		os.Rename(path, path+".old")
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	log.SetOutput(io.MultiWriter(os.Stderr, f))
	log.SetFlags(log.LstdFlags)
	log.Printf("started %s by %v (pid %d)", Version, args, os.Getpid())
}

type request struct {
	ID  int             `json:"id"`
	Cmd string          `json:"cmd"`
	Raw json.RawMessage `json:"-"`
}

type codedError struct {
	code string
	err  error
}

func (e *codedError) Error() string { return e.err.Error() }
func (e *codedError) Unwrap() error { return e.err }

func withCode(code string, err error) error { return &codedError{code, err} }

type server struct {
	out   io.Writer
	outMu sync.Mutex
	state *state
}

func serve(in io.Reader, out io.Writer) {
	s := &server{out: out, state: newState()}
	defer s.state.stopAll()
	r := bufio.NewReader(in)
	for {
		var n uint32
		if err := binary.Read(r, binary.LittleEndian, &n); err != nil {
			return // Chrome closed the port: shut down (the local proxy goes with us).
		}
		if n > 64<<20 {
			log.Printf("message too large: %d", n)
			return
		}
		buf := make([]byte, n)
		if _, err := io.ReadFull(r, buf); err != nil {
			return
		}
		var req request
		if err := json.Unmarshal(buf, &req); err != nil {
			continue
		}
		req.Raw = buf
		go s.handle(req)
	}
}

func (s *server) reply(v map[string]any) {
	b, err := json.Marshal(v)
	if err != nil {
		return
	}
	s.outMu.Lock()
	defer s.outMu.Unlock()
	binary.Write(s.out, binary.LittleEndian, uint32(len(b)))
	s.out.Write(b)
}

func (s *server) handle(req request) { s.reply(s.call(req)) }

// call runs one request and builds its reply (native messaging, or ResProxyCall on iOS).
func (s *server) call(req request) map[string]any {
	res, err := s.dispatch(req)
	if err != nil {
		code := ""
		var ce *codedError
		if errors.As(err, &ce) {
			code = ce.code
		}
		return map[string]any{"id": req.ID, "ok": false, "error": err.Error(), "code": code}
	}
	if res == nil {
		res = map[string]any{}
	}
	res["id"] = req.ID
	res["ok"] = true
	return res
}

func (s *server) dispatch(req request) (map[string]any, error) {
	st := s.state
	switch req.Cmd {
	case "hello":
		return map[string]any{"version": Version, "platform": runtime.GOOS + "/" + runtime.GOARCH}, nil
	case "start":
		var a struct {
			Upstream endpoint `json:"upstream"`
			WG       *string  `json:"wg"`
			Token    string   `json:"token"`
		}
		if err := json.Unmarshal(req.Raw, &a); err != nil {
			return nil, err
		}
		wg := ""
		if a.WG != nil {
			wg = *a.WG
		}
		port, err := st.start(a.Upstream, wg, a.Token)
		if err != nil {
			return nil, err
		}
		return map[string]any{"port": port}, nil
	case "upstream":
		var a struct {
			Upstream endpoint `json:"upstream"`
		}
		if err := json.Unmarshal(req.Raw, &a); err != nil {
			return nil, err
		}
		return nil, st.setUpstream(a.Upstream)
	case "closeConns":
		st.closeConns()
		return nil, nil
	case "probe":
		ms, err := st.probe()
		if err != nil {
			return nil, err
		}
		return map[string]any{"ms": ms}, nil
	case "status":
		return st.status(), nil
	case "vpnPing":
		var a struct {
			IP string `json:"ip"`
		}
		json.Unmarshal(req.Raw, &a)
		ms, err := st.vpnPing(a.IP)
		if err != nil {
			return nil, err
		}
		return map[string]any{"ms": ms}, nil
	case "ping":
		var a struct {
			Targets []string `json:"targets"`
		}
		json.Unmarshal(req.Raw, &a)
		return map[string]any{"results": pingAll(a.Targets)}, nil
	case "fetch":
		var a fetchArgs
		if err := json.Unmarshal(req.Raw, &a); err != nil {
			return nil, err
		}
		client := directClient
		if a.Proxy {
			client = st.proxyClient()
		}
		status, body, err := fetchWith(client, a)
		if err != nil {
			return nil, withCode("net", err)
		}
		return map[string]any{"status": status, "body": body}, nil
	case "lookup":
		var a struct {
			Name string `json:"name"`
		}
		json.Unmarshal(req.Raw, &a)
		addrs, err := lookupA(a.Name)
		if err != nil {
			return nil, err
		}
		return map[string]any{"addrs": addrs}, nil
	case "socks":
		var a struct {
			User      string `json:"user"`
			Pass      string `json:"pass"`
			LanDirect bool   `json:"lanDirect"`
		}
		if err := json.Unmarshal(req.Raw, &a); err != nil {
			return nil, err
		}
		port, err := st.startSocks(a.User, a.Pass, a.LanDirect)
		if err != nil {
			return nil, err
		}
		return map[string]any{"port": port}, nil
	case "gate":
		var a struct {
			Open bool `json:"open"`
		}
		json.Unmarshal(req.Raw, &a)
		st.setGate(a.Open)
		return nil, nil
	case "stop":
		st.stopAll()
		return nil, nil
	}
	return nil, fmt.Errorf("unknown command %q", req.Cmd)
}

func isTerminal() bool {
	fi, err := os.Stdin.Stat()
	return err == nil && fi.Mode()&os.ModeCharDevice != 0
}
