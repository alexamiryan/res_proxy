package main

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"os/exec"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ---------- direct HTTP for the extension's API calls (outside the proxy, like the Android app's own sockets) ----------

type fetchArgs struct {
	URL     string            `json:"url"`
	Method  string            `json:"method"`
	Headers map[string]string `json:"headers"`
	Body    *string           `json:"body"`
}

var directClient = &http.Client{
	Timeout:   25 * time.Second,
	Transport: &http.Transport{Proxy: nil, DialContext: (&net.Dialer{Timeout: 10 * time.Second}).DialContext, TLSHandshakeTimeout: 10 * time.Second},
}

func directFetch(a fetchArgs) (int, string, error) {
	if !strings.HasPrefix(a.URL, "https://") && !strings.HasPrefix(a.URL, "http://") {
		return 0, "", errors.New("only http(s) URLs")
	}
	var body io.Reader
	if a.Body != nil {
		body = strings.NewReader(*a.Body)
	}
	method := a.Method
	if method == "" {
		method = http.MethodGet
	}
	req, err := http.NewRequest(method, a.URL, body)
	if err != nil {
		return 0, "", err
	}
	for k, v := range a.Headers {
		req.Header.Set(k, v)
	}
	if req.Header.Get("User-Agent") == "" {
		req.Header.Set("User-Agent", "ResProxy-helper/"+Version)
	}
	resp, err := directClient.Do(req)
	if err != nil {
		return 0, "", err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if err != nil {
		return 0, "", err
	}
	return resp.StatusCode, string(b), nil
}

// lookupA resolves IPv4 addresses with the system resolver (DNSBL queries; public resolvers are often refused).
func lookupA(name string) ([]string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	defer cancel()
	ips, err := net.DefaultResolver.LookupIP(ctx, "ip4", name)
	if err != nil {
		var dnsErr *net.DNSError
		if errors.As(err, &dnsErr) && dnsErr.IsNotFound {
			return nil, withCode("nxdomain", err)
		}
		return nil, err
	}
	out := make([]string, 0, len(ips))
	for _, ip := range ips {
		out = append(out, ip.String())
	}
	return out, nil
}

// ---------- ICMP latency via the system ping (no admin rights needed) ----------

var pingTime = regexp.MustCompile(`(?i)time[=<]\s*([0-9.]+)\s*ms`)

func pingOne(target string) *int {
	var args []string
	switch runtime.GOOS {
	case "windows":
		args = []string{"-n", "1", "-w", "2000", target}
	case "darwin":
		args = []string{"-c", "1", "-t", "2", target}
	default:
		args = []string{"-c", "1", "-W", "2", target}
	}
	name := "ping"
	if strings.Contains(target, ":") && runtime.GOOS != "windows" {
		name = "ping6"
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	hideWindow(cmd)
	out, _ := cmd.Output()
	m := pingTime.FindSubmatch(out)
	if m == nil {
		return nil
	}
	f, err := strconv.ParseFloat(string(m[1]), 64)
	if err != nil {
		return nil
	}
	ms := int(f + 0.5)
	return &ms
}

func pingAll(targets []string) map[string]*int {
	res := make(map[string]*int, len(targets))
	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, 32)
	for _, t := range targets {
		if t == "" || strings.ContainsAny(t, " \t;&|`$") || strings.HasPrefix(t, "-") {
			continue
		}
		wg.Add(1)
		go func(t string) {
			defer wg.Done()
			sem <- struct{}{}
			ms := pingOne(t)
			<-sem
			mu.Lock()
			res[t] = ms
			mu.Unlock()
		}(t)
	}
	wg.Wait()
	return res
}
