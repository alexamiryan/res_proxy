package tailnet

import (
	"crypto/aes"
	"crypto/cipher"
	crand "crypto/rand"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"

	"tailscale.com/ipn"
)

// encryptedStore is an ipn.StateStore kept in one AES-256-GCM encrypted file. The node key lives in it, so it never
// sits on disk in the clear; the key comes from the Android Keystore (via the app).
type encryptedStore struct {
	path string
	aead cipher.AEAD
	mu   sync.Mutex
	m    map[ipn.StateKey][]byte
}

const storeFile = "state.enc"

var storeAAD = []byte("resproxy-tailnet-state-v1")

func newEncryptedStore(dir string, key []byte) (*encryptedStore, error) {
	if len(key) != 32 {
		return nil, errors.New("tailnet: state key must be 32 bytes")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	s := &encryptedStore{path: filepath.Join(dir, storeFile), aead: aead, m: map[ipn.StateKey][]byte{}}
	raw, err := os.ReadFile(s.path)
	switch {
	case errors.Is(err, os.ErrNotExist):
		return s, nil
	case err != nil:
		return nil, err
	}
	ns := aead.NonceSize()
	if len(raw) < ns {
		return nil, errors.New("tailnet: state file is damaged")
	}
	plain, err := aead.Open(nil, raw[:ns], raw[ns:], storeAAD)
	if err != nil {
		// A different key (app data restored elsewhere, Keystore reset): the node has to log in again.
		return nil, errors.New("tailnet: can't decrypt the saved node state (log in again)")
	}
	if err := json.Unmarshal(plain, &s.m); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *encryptedStore) ReadState(id ipn.StateKey) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	v, ok := s.m[id]
	if !ok {
		return nil, ipn.ErrStateNotExist
	}
	return append([]byte(nil), v...), nil
}

func (s *encryptedStore) WriteState(id ipn.StateKey, bs []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if bs == nil {
		delete(s.m, id)
	} else {
		s.m[id] = append([]byte(nil), bs...)
	}
	return s.save()
}

func (s *encryptedStore) save() error {
	plain, err := json.Marshal(s.m)
	if err != nil {
		return err
	}
	nonce := make([]byte, s.aead.NonceSize())
	if _, err := crand.Read(nonce); err != nil {
		return err
	}
	out := s.aead.Seal(nonce, nonce, plain, storeAAD)
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, out, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}
