package subscriptionbridge

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"os"
	"strings"
	"testing"

	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
)

const (
	syntheticSupported   = "vless" + "://" + "synthetic-id@node.invalid:443?security=tls"
	syntheticUnsupported = "unknown" + "://" + "node.invalid:1"
)

func TestSnapshotRecoveryPreservesOpaqueIDsAndProfiles(t *testing.T) {
	raw := []byte(syntheticSupported + "\n" + syntheticUnsupported + "\n")
	s, err := parseSnapshot(context.Background(), raw)
	if err != nil || s.Status() != 1 || s.RecordCount() != 2 {
		t.Fatalf("parse snapshot: err=%v", err)
	}
	defer s.Close()
	record, err := s.RecordJSON(0)
	if err != nil {
		t.Fatal(err)
	}
	var before struct {
		ID string `json:"id"`
	}
	if json.Unmarshal(record, &before) != nil || before.ID == "" {
		t.Fatal("record ID")
	}
	f, err := os.CreateTemp(t.TempDir(), "snapshot")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	n, err := s.WriteSnapshot(int64(f.Fd()))
	if err != nil {
		t.Fatal(err)
	}
	recovered, err := ReadSnapshot(int64(f.Fd()), n)
	if err != nil {
		t.Fatal(err)
	}
	defer recovered.Close()
	after, err := recovered.RecordJSON(0)
	if err != nil || !bytes.Equal(record, after) {
		t.Fatal("opaque identity changed")
	}
	ids, _ := json.Marshal([]string{before.ID})
	out1, err := os.CreateTemp(t.TempDir(), "profiles1")
	if err != nil {
		t.Fatal(err)
	}
	defer out1.Close()
	out2, err := os.CreateTemp(t.TempDir(), "profiles2")
	if err != nil {
		t.Fatal(err)
	}
	defer out2.Close()
	if _, err = s.WriteSelected(ids, int64(out1.Fd())); err != nil {
		t.Fatal(err)
	}
	if _, err = recovered.WriteSelected(ids, int64(out2.Fd())); err != nil {
		t.Fatal(err)
	}
	a, _ := os.ReadFile(out1.Name())
	b, _ := os.ReadFile(out2.Name())
	if !bytes.Equal(a, b) || !bytes.Contains(a, []byte("synthetic-id")) {
		t.Fatal("private profile changed")
	}
	if _, err := f.WriteAt([]byte{0xff}, n-1); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadSnapshot(int64(f.Fd()), n); err == nil || err.Error() != "subscription_snapshot_invalid" {
		t.Fatalf("changed fingerprint accepted: %v", err)
	}
}

func TestSnapshotSelectionRejectsUnknownUnsupportedAndDuplicateWithoutWriting(t *testing.T) {
	s, err := parseSnapshot(context.Background(), []byte(syntheticSupported+"\n"+syntheticUnsupported+"\n"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	out, err := os.CreateTemp(t.TempDir(), "selection")
	if err != nil {
		t.Fatal(err)
	}
	defer out.Close()
	for _, ids := range [][]string{
		{},
		{s.stableIDs[0], "unknown"},
		{s.stableIDs[1]},
		{s.stableIDs[0], s.stableIDs[0]},
	} {
		if err := out.Truncate(0); err != nil {
			t.Fatal(err)
		}
		if _, err := out.WriteAt([]byte("unchanged"), 0); err != nil {
			t.Fatal(err)
		}
		input, _ := json.Marshal(ids)
		if _, err := s.WriteSelected(input, int64(out.Fd())); err != errInput {
			t.Fatalf("invalid selection accepted: %v", err)
		}
		data, err := os.ReadFile(out.Name())
		if err != nil || string(data) != "unchanged" {
			t.Fatal("invalid selection changed the destination")
		}
	}
}

func TestSnapshotRejectsChangedFingerprintEvenWithValidChecksum(t *testing.T) {
	s, err := parseSnapshot(context.Background(), []byte(syntheticSupported+"\n"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	f, err := os.CreateTemp(t.TempDir(), "snapshot")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	n, err := s.WriteSnapshot(int64(f.Fd()))
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(f.Name())
	if err != nil {
		t.Fatal(err)
	}
	rawLen := int(binary.BigEndian.Uint32(data[4:8]))
	data[snapshotHeaderBytes+rawLen+17] ^= 1
	sum := sha256.Sum256(data[snapshotHeaderBytes:])
	copy(data[12:44], sum[:])
	if _, err := f.WriteAt(data, 0); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadSnapshot(int64(f.Fd()), n); err != errSnapshot {
		t.Fatal("mismatched record fingerprint accepted")
	}
}

func TestSelectedProfilesCanonicalSizeBoundary(t *testing.T) {
	const prefix = "vless" + "://" + "synthetic-id@node.invalid:443?type=ws&path=/"
	base, err := parseSnapshot(context.Background(), []byte(prefix))
	if err != nil {
		t.Fatal(err)
	}
	profile, ok := base.batch.Profile(base.batch.Records[0].ID)
	if !ok {
		t.Fatal("synthetic profile missing")
	}
	encoded, err := json.Marshal(profile)
	if err != nil {
		t.Fatal(err)
	}
	base.Close()
	padding := importer.MaxInputBytes - (len(encoded) + 2)
	for _, delta := range []int{-1, 0, 1} {
		raw := prefix + strings.Repeat("a", padding+delta)
		s, err := parseSnapshot(context.Background(), []byte(raw))
		if err != nil {
			t.Fatalf("parse at delta %d: %v", delta, err)
		}
		p, _ := s.batch.Profile(s.batch.Records[0].ID)
		serialized, err := json.Marshal(p)
		if err != nil || len(serialized)+2 != importer.MaxInputBytes+delta {
			t.Fatalf("test setup at delta %d", delta)
		}
		ids, _ := json.Marshal([]string{s.stableIDs[0]})
		out, err := os.CreateTemp(t.TempDir(), "profiles")
		if err != nil {
			t.Fatal(err)
		}
		if _, err := out.Write([]byte("unchanged")); err != nil {
			t.Fatal(err)
		}
		n, err := s.WriteSelected(ids, int64(out.Fd()))
		if delta > 0 {
			data, readErr := os.ReadFile(out.Name())
			if err != errSize || readErr != nil || string(data) != "unchanged" {
				t.Fatal("oversized selection changed the destination")
			}
		} else if err != nil || n != int64(importer.MaxInputBytes+delta) {
			t.Fatalf("selection at delta %d: %v, %d", delta, err, n)
		}
		out.Close()
		s.Close()
	}
}

func TestSnapshotNoSupportedRetainsRecords(t *testing.T) {
	s, err := parseSnapshot(context.Background(), []byte(syntheticUnsupported+"\n"))
	if err != nil || s.Status() != 2 || s.RecordCount() != 1 {
		t.Fatalf("no-supported snapshot: %v", err)
	}
	defer s.Close()
	record, err := s.RecordJSON(0)
	if err != nil || !bytes.Contains(record, []byte("unsupported_record")) {
		t.Fatal("unsupported record lost")
	}
}
