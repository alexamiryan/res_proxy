package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

// hostManifest is Chrome's native messaging host manifest.
func hostManifest(exe, extensionID string) ([]byte, error) {
	return json.MarshalIndent(map[string]any{
		"name":            hostName,
		"description":     "ResProxy helper: local proxy with WireGuard chaining for the ResProxy extension",
		"path":            exe,
		"type":            "stdio",
		"allowed_origins": []string{"chrome-extension://" + extensionID + "/"},
	}, "", "  ")
}

func install(extensionID string) error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	if exe, err = filepath.EvalSymlinks(exe); err != nil {
		return err
	}
	m, err := hostManifest(exe, extensionID)
	if err != nil {
		return err
	}
	browsers, err := register(exe, m)
	if err != nil {
		return err
	}
	fmt.Printf("Registered %s for %v.\n", exe, browsers)
	fmt.Println("Keep the helper at this path (run install again after moving it). Restart is not needed.")
	return nil
}

func uninstall() error { return unregister() }
