package state

import (
	"encoding/json"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"os"
	"path/filepath"
	"testing"
)

// The literal is the pre-media schema; decoding/re-encoding must retain its hash.
func TestLegacyProbeJournalReplayGolden(t *testing.T) {
	old := `{"id":"legacy","probe":"page","round":0,"transport":"https-tcp","outcome":"pass","phase":"body","bytes":1024,"duration_ms":10,"tls_verified":true,"redirects":0}`
	var p model.ProbeResult
	if model.Decode([]byte(old), &p) != nil {
		t.Fatal("legacy decode")
	}
	raw, e := json.Marshal(p)
	if e != nil || string(raw) != old {
		t.Fatal("legacy encoding changed", string(raw), e)
	}
	s, e := Open(t.TempDir(), 0)
	if e != nil {
		t.Fatal(e)
	}
	dir := s.Dir
	if e = s.Begin(model.Attempt{ID: "legacy-attempt", Candidate: model.Candidate{ID: "candidate"}}, "1"); e != nil {
		t.Fatal(e)
	}
	if e = s.Probe(p); e != nil {
		t.Fatal(e)
	}
	if e = s.Close(); e != nil {
		t.Fatal(e)
	}
	before, e := os.ReadFile(filepath.Join(dir, "journal.jsonl"))
	if e != nil {
		t.Fatal(e)
	}
	s, e = Open(dir, 0)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	after, e := os.ReadFile(filepath.Join(dir, "journal.jsonl"))
	if e != nil || string(before) != string(after) || s.View().Pending.Results[0].Media != nil {
		t.Fatal("legacy replay changed journal", e)
	}
}
