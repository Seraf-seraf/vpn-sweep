package state

import (
	"crypto/rand"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/private"
	"os"
	"path/filepath"
)

// One key per private installation root keeps candidate identity independent of series/network.
func IdentityKey(parent string) ([]byte, error) {
	d, e := private.Dir(parent)
	if e != nil {
		return nil, e
	}
	path := filepath.Join(d, "candidate-identity.key")
	if _, e = os.Lstat(path); e == nil {
		b, e := private.Read(path, 32)
		if e != nil || len(b) != 32 {
			return nil, model.Err("identity_key_invalid", "state")
		}
		return b, nil
	} else if !os.IsNotExist(e) {
		return nil, model.Err("identity_key_unavailable", "state")
	}
	key := make([]byte, 32)
	if _, e = rand.Read(key); e != nil {
		return nil, model.Err("random_unavailable", "state")
	}
	f, e := private.Open(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY)
	if e != nil {
		return nil, model.Err("identity_key_creation_conflict_retry", "state")
	}
	defer f.Close()
	n, e := f.Write(key)
	if e != nil || n != 32 {
		return nil, model.Err("identity_key_write_failed", "state")
	}
	if e = f.Sync(); e != nil {
		return nil, model.Err("identity_key_sync_failed", "state")
	}
	if e = private.SyncDir(d); e != nil {
		return nil, model.Err("identity_key_sync_failed", "state")
	}
	return key, nil
}
