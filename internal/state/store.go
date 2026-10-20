package state

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/private"
	"io"
	"os"
	"path/filepath"
	"sync"
	"syscall"
	"time"
)

const Version = 1
const maxRecordBytes = 1 << 20

type manifestEnvelope struct {
	Manifest  model.Manifest `json:"manifest"`
	Integrity string         `json:"integrity"`
}

type Event struct {
	CandidateID string             `json:"candidate_id,omitempty"`
	Refinements []model.Candidate  `json:"refinements,omitempty"`
	Kind        string             `json:"kind"`
	Attempt     *model.Attempt     `json:"attempt,omitempty"`
	Probe       *model.ProbeResult `json:"probe,omitempty"`
	Next        string             `json:"next,omitempty"`
	Status      string             `json:"status,omitempty"`
	At          time.Time          `json:"at"`
}
type record struct {
	Version  int             `json:"version"`
	Sequence uint64          `json:"sequence"`
	Previous string          `json:"previous"`
	Event    json.RawMessage `json:"event"`
	Hash     string          `json:"hash"`
}
type View struct {
	Refinements  []model.Candidate `json:"refinements,omitempty"`
	Next         string            `json:"next"`
	Status       string            `json:"status"`
	Attempts     uint64            `json:"attempts"`
	PayloadBytes int64             `json:"payload_bytes"`
	Pending      *model.Attempt    `json:"pending,omitempty"`
	Winner       *model.Attempt    `json:"winner,omitempty"`
	LastSaved    *model.Attempt    `json:"last_saved,omitempty"`
	Inconclusive uint64            `json:"inconclusive"`
	Retry        *model.Candidate  `json:"retry,omitempty"`
}
type Store struct {
	mu       sync.Mutex
	Dir      string
	lock     *os.File
	file     *os.File
	seq      uint64
	hash     string
	view     View
	poisoned bool
	maxBytes int64
	seen     map[string]bool
	write    func([]byte) (int, error)
	syncFile func() error
}

func Open(dir string, maxBytes int64) (*Store, error) {
	d, e := private.Dir(dir)
	if e != nil {
		return nil, e
	}
	l, e := private.Open(filepath.Join(d, "writer.lock"), os.O_CREATE|os.O_RDWR)
	if e != nil {
		return nil, e
	}
	if e = syscall.Flock(int(l.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); e != nil {
		l.Close()
		return nil, model.Err("state_locked", "state")
	}
	f, e := private.Open(filepath.Join(d, "journal.jsonl"), os.O_CREATE|os.O_RDWR)
	if e != nil {
		l.Close()
		return nil, e
	}
	s := &Store{Dir: d, lock: l, file: f, maxBytes: maxBytes, view: View{Next: "0", Status: "ready"}, seen: map[string]bool{}}
	s.write = f.Write
	s.syncFile = f.Sync
	if e = s.replay(); e != nil {
		s.Close()
		return nil, e
	}
	if e = private.SyncDir(d); e != nil {
		s.Close()
		return nil, model.Err("sync_failed", "state")
	}
	return s, nil
}
func (s *Store) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	var e error
	if s.file != nil {
		e = s.file.Close()
		s.file = nil
	}
	if s.lock != nil {
		if x := s.lock.Close(); e == nil {
			e = x
		}
		s.lock = nil
	}
	return e
}
func hashRecord(r record) string {
	r.Hash = ""
	b, _ := json.Marshal(r)
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}
func (s *Store) replay() error {
	if _, e := s.file.Seek(0, io.SeekStart); e != nil {
		return e
	}
	rd := bufio.NewReaderSize(s.file, maxRecordBytes+1)
	var offset int64
	for {
		line, e := rd.ReadSlice('\n')
		if e == io.EOF {
			if len(line) > 0 {
				if e = s.file.Truncate(offset); e != nil {
					return model.Err("recovery_truncate_failed", "state")
				}
				if e = s.file.Sync(); e != nil {
					return model.Err("sync_failed", "state")
				}
			}
			break
		}
		if e != nil || len(line) > maxRecordBytes {
			return model.Err("journal_corrupt", "state")
		}
		var r record
		if model.Decode(bytes.TrimSpace(line), &r) != nil || r.Version != Version || r.Sequence != s.seq+1 || r.Previous != s.hash || r.Hash != hashRecord(r) {
			return model.Err("journal_integrity_or_version", "state")
		}
		var ev Event
		if model.Decode(r.Event, &ev) != nil {
			return model.Err("journal_corrupt", "state")
		}
		if e = s.apply(ev); e != nil {
			return e
		}
		s.seq = r.Sequence
		s.hash = r.Hash
		offset += int64(len(line))
	}
	_, e := s.file.Seek(0, io.SeekEnd)
	return e
}
func (s *Store) apply(e Event) error {
	switch e.Kind {
	case "begin":
		if s.view.Pending != nil || e.Attempt == nil || e.Next == "" {
			return model.Err("invalid_transition", "state")
		}
		s.view.Pending = e.Attempt
		s.view.Retry = nil
		if len(s.view.Refinements) > 0 && s.view.Refinements[0].ID == e.Attempt.Candidate.ID {
			s.view.Refinements = s.view.Refinements[1:]
		}
		s.view.Next = e.Next
		s.view.Attempts++
	case "probe":
		if s.view.Pending == nil || e.Probe == nil {
			return model.Err("invalid_transition", "state")
		}
		s.view.Pending.Results = append(s.view.Pending.Results, *e.Probe)
		s.view.PayloadBytes += e.Probe.Bytes
	case "restart":
		if s.view.Pending == nil {
			return model.Err("invalid_transition", "state")
		}
		s.view.Pending.Restarts++
	case "end":
		if s.view.Pending == nil {
			return model.Err("invalid_transition", "state")
		}
		s.view.Pending.Outcome = e.Status
		if e.Status == "interrupted" {
			c := s.view.Pending.Candidate
			s.view.Retry = &c
		} else {
			s.seen[s.view.Pending.Candidate.ID] = true
		}
		if e.Status == "verified_pending_export" {
			s.view.Winner = s.view.Pending
		}
		if e.Status == "inconclusive" {
			s.view.Inconclusive++
		}
		s.view.Pending = nil
	case "status":
		s.view.Status = e.Status
	case "saved":
		if s.view.Pending != nil || s.view.Winner == nil || e.CandidateID != s.view.Winner.Candidate.ID {
			return model.Err("invalid_transition", "state")
		}
		s.view.LastSaved = s.view.Winner
		s.view.Winner = nil
		s.view.Status = "ready"
	case "refine":
		if len(s.view.Refinements)+len(e.Refinements) > 32 {
			return model.Err("refinement_queue_limit", "state")
		}
		s.view.Refinements = append(s.view.Refinements, e.Refinements...)
	default:
		return model.Err("unknown_event_version", "state")
	}
	return nil
}

func (s *Store) Seen(id string) bool { s.mu.Lock(); defer s.mu.Unlock(); return s.seen[id] }

func (s *Store) Refine(candidates []model.Candidate) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.view.Refinements) >= 32 {
		return nil
	}
	pending := map[string]bool{}
	for _, c := range s.view.Refinements {
		pending[c.ID] = true
	}
	q := []model.Candidate{}
	for _, c := range candidates {
		if !s.seen[c.ID] && !pending[c.ID] {
			q = append(q, c)
			pending[c.ID] = true
		}
		if len(q)+len(s.view.Refinements) >= 32 {
			break
		}
	}
	if len(q) == 0 {
		return nil
	}
	return s.append(Event{Kind: "refine", Refinements: q})
}
func (s *Store) append(e Event) error {
	if s.poisoned {
		return model.Err("state_requires_reopen", "state")
	}
	e.At = time.Now().UTC()
	payload, err := json.Marshal(e)
	if err != nil {
		return err
	}
	r := record{Version: Version, Sequence: s.seq + 1, Previous: s.hash, Event: payload}
	r.Hash = hashRecord(r)
	b, _ := json.Marshal(r)
	b = append(b, '\n')
	if len(b) > maxRecordBytes {
		return model.Err("record_limit", "state")
	}
	st, err := s.file.Stat()
	if err != nil {
		return err
	}
	if s.maxBytes > 0 && st.Size()+int64(len(b)) > s.maxBytes {
		return model.Err("journal_budget", "state")
	}
	n, err := s.write(b)
	if err == nil && n != len(b) {
		err = io.ErrShortWrite
	}
	if err == nil {
		err = s.syncFile()
	}
	if err != nil {
		s.poisoned = true
		return model.Err("journal_write_or_sync_failed", "state")
	}
	if err = s.apply(e); err != nil {
		s.poisoned = true
		return err
	}
	s.seq++
	s.hash = r.Hash
	return nil
}
func (s *Store) Begin(a model.Attempt, next string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.view.Pending != nil || s.view.Winner != nil {
		return model.Err("attempt_pending_or_winner", "state")
	}
	return s.append(Event{Kind: "begin", Attempt: &a, Next: next})
}
func (s *Store) Probe(r model.ProbeResult) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.view.Pending == nil {
		return model.Err("no_attempt", "state")
	}
	return s.append(Event{Kind: "probe", Probe: &r})
}
func (s *Store) Restart() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.view.Pending == nil {
		return model.Err("no_attempt", "state")
	}
	return s.append(Event{Kind: "restart"})
}
func (s *Store) End(status string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.view.Pending == nil {
		return model.Err("no_attempt", "state")
	}
	return s.append(Event{Kind: "end", Status: status})
}
func (s *Store) Status(status string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.append(Event{Kind: "status", Status: status})
}
func (s *Store) MarkSaved(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.view.Pending != nil || s.view.Winner == nil || s.view.Winner.Candidate.ID != id {
		return model.Err("invalid_transition", "state")
	}
	return s.append(Event{Kind: "saved", CandidateID: id})
}
func (s *Store) View() View {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, _ := json.Marshal(s.view)
	var v View
	json.Unmarshal(b, &v)
	return v
}
func (s *Store) History(w io.Writer) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	f, e := private.Open(filepath.Join(s.Dir, "journal.jsonl"), os.O_RDONLY)
	if e != nil {
		return e
	}
	defer f.Close()
	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 65536), maxRecordBytes)
	encoder := json.NewEncoder(w)
	for scanner.Scan() {
		var r record
		if model.Decode(scanner.Bytes(), &r) != nil {
			return model.Err("journal_corrupt", "state")
		}
		var ev Event
		if model.Decode(r.Event, &ev) != nil {
			return model.Err("journal_corrupt", "state")
		}
		if ev.Attempt != nil {
			ev.Attempt.Candidate.Params = nil
		}
		for i := range ev.Refinements {
			ev.Refinements[i].Params = nil
		}
		if e = encoder.Encode(ev); e != nil {
			return e
		}
	}
	return scanner.Err()
}
func (s *Store) SaveManifest(m model.Manifest, profiles []model.Profile, key []byte) error {
	if m.Version != model.SchemaVersion {
		return model.Err("unsupported_manifest_version", "state")
	}
	for _, name := range []string{"manifest.json", "profiles.json", "identity.key"} {
		if _, e := os.Lstat(filepath.Join(s.Dir, name)); e == nil {
			return model.Err("series_exists", "state")
		}
	}
	if e := private.JSON(filepath.Join(s.Dir, "profiles.json"), profiles); e != nil {
		return e
	}
	if e := private.Atomic(filepath.Join(s.Dir, "identity.key"), key); e != nil {
		return e
	}
	return private.JSON(filepath.Join(s.Dir, "manifest.json"), manifestEnvelope{Manifest: m, Integrity: model.PrivateID(key, m)})
}
func (s *Store) LoadManifest() (model.Manifest, []model.Profile, []byte, error) {
	var m model.Manifest
	var p []model.Profile
	b, e := private.Read(filepath.Join(s.Dir, "manifest.json"), 16<<20)
	if e != nil {
		return m, p, nil, e
	}
	var envelope manifestEnvelope
	if e = model.Decode(b, &envelope); e != nil || envelope.Manifest.Version != model.SchemaVersion {
		return m, p, nil, model.Err("unsupported_manifest_version", "state")
	}
	m = envelope.Manifest
	b, e = private.Read(filepath.Join(s.Dir, "profiles.json"), 16<<20)
	if e != nil {
		return m, p, nil, e
	}
	if e = model.Decode(b, &p); e != nil {
		return m, p, nil, e
	}
	k, e := private.Read(filepath.Join(s.Dir, "identity.key"), 32)
	if e != nil || len(k) != 32 {
		return m, p, nil, model.Err("identity_key_invalid", "state")
	}
	if envelope.Integrity != model.PrivateID(k, m) {
		return m, p, nil, model.Err("manifest_integrity_failed", "state")
	}
	if m.InputID != model.PrivateID(k, p) {
		return m, p, nil, model.Err("input_snapshot_changed", "state")
	}
	return m, p, k, nil
}
func (s *Store) String() string { return fmt.Sprintf("journal seq=%d", s.seq) }
