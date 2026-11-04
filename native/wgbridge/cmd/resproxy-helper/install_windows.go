//go:build windows

package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"syscall"

	"golang.org/x/sys/windows/registry"
)

var regBrowsers = map[string]string{
	"Chrome":   `Software\Google\Chrome\NativeMessagingHosts\`,
	"Edge":     `Software\Microsoft\Edge\NativeMessagingHosts\`,
	"Brave":    `Software\BraveSoftware\Brave-Browser\NativeMessagingHosts\`,
	"Chromium": `Software\Chromium\NativeMessagingHosts\`,
}

// register writes the manifest next to the exe and points every browser's HKCU key at it.
func register(exe string, manifest []byte) ([]string, error) {
	path := filepath.Join(filepath.Dir(exe), hostName+".json")
	if err := os.WriteFile(path, manifest, 0o644); err != nil {
		return nil, err
	}
	var done []string
	for name, base := range regBrowsers {
		k, _, err := registry.CreateKey(registry.CURRENT_USER, base+hostName, registry.SET_VALUE)
		if err != nil {
			continue
		}
		err = k.SetStringValue("", path)
		k.Close()
		if err == nil {
			done = append(done, name)
		}
	}
	return done, nil
}

func unregister() error {
	for _, base := range regBrowsers {
		registry.DeleteKey(registry.CURRENT_USER, base+hostName)
	}
	if exe, err := os.Executable(); err == nil {
		os.Remove(filepath.Join(filepath.Dir(exe), hostName+".json"))
	}
	return nil
}

// hideWindow keeps ping.exe from flashing a console window.
func hideWindow(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000} // CREATE_NO_WINDOW
}
