package state

import (
	"bytes"
	"errors"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"testing"
)

func attempt() model.Attempt {
	return model.Attempt{ID: "attempt-a", Candidate: model.Candidate{ID: "candidate-a", Params: map[string]any{"fragment": false}}}
}
func TestAtomicBeginAndEveryTornPrefix(t *testing.T) {
	d := t.TempDir()
	s, e := Open(d, 0)
	if e != nil {
		t.Fatal(e)
	}
	if e = s.Begin(attempt(), "1"); e != nil {
		t.Fatal(e)
	}
	s.Close()
	data, _ := os.ReadFile(filepath.Join(d, "journal.jsonl"))
	for cut := 0; cut <= len(data); cut++ {
		dir := t.TempDir()
		if e = os.WriteFile(filepath.Join(dir, "journal.jsonl"), data[:cut], 0600); e != nil {
			t.Fatal(e)
		}
		r, e := Open(dir, 0)
		if e != nil {
			t.Fatal(cut, e)
		}
		v := r.View()
		if cut < len(data) {
			if v.Next != "0" || v.Pending != nil || v.Attempts != 0 {
				t.Fatal("partially advanced issuance", cut)
			}
		} else {
			if v.Next != "1" || v.Pending == nil || v.Attempts != 1 {
				t.Fatal("lost committed attempt")
			}
		}
		r.Close()
	}
}
func TestFaultWriteAndSyncRecovery(t *testing.T) {
	for _, kind := range []string{"partial", "sync"} {
		t.Run(kind, func(t *testing.T) {
			d := t.TempDir()
			s, _ := Open(d, 0)
			if kind == "partial" {
				s.write = func(b []byte) (int, error) { return s.file.Write(b[:len(b)/2]) }
			} else {
				s.syncFile = func() error { return io.ErrClosedPipe }
			}
			if s.Begin(attempt(), "1") == nil {
				t.Fatal("fault hidden")
			}
			if s.View().Next != "0" {
				t.Fatal("unacknowledged memory advance")
			}
			if s.Begin(attempt(), "1") == nil {
				t.Fatal("poisoned store reused")
			}
			s.Close()
			r, e := Open(d, 0)
			if e != nil {
				t.Fatal(e)
			}
			defer r.Close()
			v := r.View()
			if kind == "partial" && v.Pending != nil {
				t.Fatal("torn issue committed")
			}
			if kind == "sync" && v.Pending == nil {
				t.Fatal("complete ambiguous write was skipped")
			}
		})
	}
}
func TestIntegrityAndVersions(t *testing.T) {
	d := t.TempDir()
	s, _ := Open(d, 0)
	s.Begin(attempt(), "1")
	s.Close()
	p := filepath.Join(d, "journal.jsonl")
	b, _ := os.ReadFile(p)
	b = bytes.Replace(b, []byte("candidate-a"), []byte("candidate-b"), 1)
	os.WriteFile(p, b, 0600)
	if r, e := Open(d, 0); e == nil {
		r.Close()
		t.Fatal("corruption accepted")
	}
	dir := t.TempDir()
	s, _ = Open(dir, 0)
	if s.SaveManifest(model.Manifest{Version: 2}, nil, nil) == nil {
		t.Fatal("unknown version accepted")
	}
	s.Close()
}
func TestSingleWriterAndConcurrentBegin(t *testing.T) {
	d := t.TempDir()
	s, _ := Open(d, 0)
	defer s.Close()
	if other, e := Open(d, 0); e == nil {
		other.Close()
		t.Fatal("second writer")
	}
	var wg sync.WaitGroup
	var mu sync.Mutex
	ok := 0
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if s.Begin(attempt(), "1") == nil {
				mu.Lock()
				ok++
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	if ok != 1 {
		t.Fatal("multiple begin", ok)
	}
	if s.End("interrupted") != nil {
		t.Fatal("end")
	}
	a := attempt()
	a.ID = "attempt-b"
	if s.Begin(a, "1") != nil {
		t.Fatal("retry same candidate forbidden")
	}
	if s.View().Pending.ID != "attempt-b" || s.View().Attempts != 2 {
		t.Fatal("retry identity lost")
	}
}
func TestCrashChild(t *testing.T) {
	if os.Getenv("VPN_SWEEP_TEST_CRASH") == "1" {
		s, e := Open(os.Getenv("VPN_SWEEP_TEST_DIR"), 0)
		if e != nil {
			os.Exit(72)
		}
		s.write = func(b []byte) (int, error) { s.file.Write(b[:len(b)/2]); s.file.Sync(); os.Exit(71); return 0, nil }
		s.Begin(attempt(), "1")
		os.Exit(73)
	}
	d := t.TempDir()
	cmd := exec.Command(os.Args[0], "-test.run=^TestCrashChild$")
	cmd.Env = append(os.Environ(), "VPN_SWEEP_TEST_CRASH=1", "VPN_SWEEP_TEST_DIR="+d)
	e := cmd.Run()
	var exit *exec.ExitError
	if !errors.As(e, &exit) || exit.ExitCode() != 71 {
		t.Fatal("crash helper", e)
	}
	s, e := Open(d, 0)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	if s.View().Next != "0" || s.View().Pending != nil {
		t.Fatal("crash skipped candidate")
	}
}
func TestProbeRecoveryAndBudget(t *testing.T) {
	d := t.TempDir()
	s, _ := Open(d, 0)
	s.Begin(attempt(), "1")
	s.Probe(model.ProbeResult{ID: "p1", Bytes: 19, Outcome: "pass"})
	s.Restart()
	s.Close()
	s, e := Open(d, 0)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	v := s.View()
	if v.PayloadBytes != 19 || len(v.Pending.Results) != 1 || v.Pending.Restarts != 1 {
		t.Fatal("raw results lost")
	}
	s.maxBytes = 1
	if model.Code(s.Status("pause")) != "journal_budget" {
		t.Fatal("budget")
	}
}

func TestRefinementIssuanceAndInterruptedRecovery(t *testing.T) {
	d := t.TempDir()
	s, _ := Open(d, 0)
	c := attempt().Candidate
	if e := s.Refine([]model.Candidate{c}); e != nil {
		t.Fatal(e)
	}
	s.Begin(attempt(), "0")
	if len(s.View().Refinements) != 0 {
		t.Fatal("queue not consumed atomically")
	}
	s.End("interrupted")
	s.Close()
	s, e := Open(d, 0)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	if s.View().Retry == nil || s.View().Retry.ID != c.ID || s.View().Next != "0" {
		t.Fatal("refinement lost on interruption")
	}
}

func TestManifestIntegrityAndPrivateInput(t *testing.T) {
	d := t.TempDir()
	s, _ := Open(d, 0)
	defer s.Close()
	key := bytes.Repeat([]byte{3}, 32)
	p := []model.Profile{{Outbound: map[string]any{"synthetic": "secret-marker"}}}
	m := model.Manifest{Version: 1, SeriesID: "test", InputID: model.PrivateID(key, p)}
	if e := s.SaveManifest(m, p, key); e != nil {
		t.Fatal(e)
	}
	if _, _, _, e := s.LoadManifest(); e != nil {
		t.Fatal(e)
	}
	path := filepath.Join(d, "manifest.json")
	b, _ := os.ReadFile(path)
	b = bytes.Replace(b, []byte(`"series_id": "test"`), []byte(`"series_id": "evil"`), 1)
	os.WriteFile(path, b, 0600)
	if _, _, _, e := s.LoadManifest(); model.Code(e) != "manifest_integrity_failed" {
		t.Fatal("manifest tamper", e)
	}
}

func TestInstallationIdentityIndependentOfSeries(t *testing.T) {
	d := t.TempDir()
	a, e := IdentityKey(d)
	if e != nil {
		t.Fatal(e)
	}
	b, e := IdentityKey(d)
	if e != nil || !bytes.Equal(a, b) {
		t.Fatal("candidate identity changed by new series")
	}
	other, e := IdentityKey(t.TempDir())
	if e != nil || bytes.Equal(a, other) {
		t.Fatal("installation keys not isolated")
	}
}
