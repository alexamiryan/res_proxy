//go:build !windows

package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
)

func manifestDirs() map[string]string {
	home, _ := os.UserHomeDir()
	if runtime.GOOS == "darwin" {
		base := filepath.Join(home, "Library", "Application Support")
		return map[string]string{
			"Chrome":   filepath.Join(base, "Google", "Chrome", "NativeMessagingHosts"),
			"Chromium": filepath.Join(base, "Chromium", "NativeMessagingHosts"),
			"Brave":    filepath.Join(base, "BraveSoftware", "Brave-Browser", "NativeMessagingHosts"),
			"Edge":     filepath.Join(base, "Microsoft Edge", "NativeMessagingHosts"),
		}
	}
	base := filepath.Join(home, ".config")
	return map[string]string{
		"Chrome":   filepath.Join(base, "google-chrome", "NativeMessagingHosts"),
		"Chromium": filepath.Join(base, "chromium", "NativeMessagingHosts"),
		"Brave":    filepath.Join(base, "BraveSoftware", "Brave-Browser", "NativeMessagingHosts"),
		"Edge":     filepath.Join(base, "microsoft-edge", "NativeMessagingHosts"),
	}
}

func register(exe string, manifest []byte) ([]string, error) {
	var done []string
	for name, dir := range manifestDirs() {
		// Only browsers that exist (their profile folder is there).
		if _, err := os.Stat(filepath.Dir(dir)); err != nil {
			continue
		}
		if err := os.MkdirAll(dir, 0o755); err != nil {
			continue
		}
		if os.WriteFile(filepath.Join(dir, hostName+".json"), manifest, 0o644) == nil {
			done = append(done, name)
		}
	}
	return done, nil
}

func unregister() error {
	for _, dir := range manifestDirs() {
		os.Remove(filepath.Join(dir, hostName+".json"))
	}
	return nil
}

func hideWindow(*exec.Cmd) {}
