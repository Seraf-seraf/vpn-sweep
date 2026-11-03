package mobile

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"sync"

	"github.com/Seraf-seraf/vpn-sweep/internal/export"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

const (
	// The export reader applies the same limit to each verified file.
	maxSearchResultBytes      int64 = 16 << 20
	maxSearchResultChunkBytes int64 = 64 << 10
)

var errSearchResultCleanupUnknown = model.Err("export_cleanup_unknown", "export")

// SearchVerifiedResult owns its result stream until Close succeeds.
// The consumer must verify Digest against the bytes it actually reads.
type SearchVerifiedResult struct {
	mu        sync.Mutex
	file      io.ReadCloser
	selection map[string]any
	digest    string
	read      int64
	readErr   error
	closed    bool
	closeErr  error
}

// TakeVerifiedResult can be called once after Run returns success. The caller
// must first confirm worker cleanup, death and release of all lease FDs.
func (s *SearchSession) TakeVerifiedResult() (*SearchVerifiedResult, error) {
	result, err := s.takeVerifiedResult(export.OpenVerifiedResult)
	if err != nil {
		return nil, err
	}
	return result.withSelection()
}

func (s *SearchSession) takeVerifiedResult(open func(string, string, string) (*os.File, string, error)) (*SearchVerifiedResult, error) {
	s.mu.Lock()
	if s.closed || s.running || s.openingResult || !s.resultReady || s.resultClaimed || s.runner == nil || s.store == nil || open == nil {
		s.mu.Unlock()
		return nil, errors.New("search_result_unavailable")
	}
	winner := s.store.View().Winner
	if winner == nil || winner.Candidate.ID == "" {
		s.mu.Unlock()
		return nil, errors.New("search_result_unavailable")
	}
	dir, series, candidate := s.stagingDir, s.runner.Manifest.SeriesID, winner.Candidate.ID
	s.resultClaimed = true
	s.openingResult = true
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		s.openingResult = false
		s.mu.Unlock()
	}()

	file, digest, err := open(dir, series, candidate)
	if err != nil || file == nil || len(digest) != 64 {
		if file != nil && file.Close() != nil {
			return nil, errSearchResultCleanupUnknown
		}
		if model.Code(err) == "export_cleanup_unknown" {
			return nil, errSearchResultCleanupUnknown
		}
		return nil, errors.New("search_result_unavailable")
	}
	if _, err := hex.DecodeString(digest); err != nil {
		if file.Close() != nil {
			return nil, errSearchResultCleanupUnknown
		}
		return nil, errors.New("search_result_unavailable")
	}
	return &SearchVerifiedResult{file: file, digest: digest, selection: winner.Candidate.Params}, nil
}

// withSelection binds the winning search parameters to the Android result.
// The original export is verified before the private envelope is augmented.
func (r *SearchVerifiedResult) withSelection() (*SearchVerifiedResult, error) {
	data, err := io.ReadAll(io.LimitReader(r.file, maxConfigBytes+1))
	closeErr := r.Close()
	if closeErr != nil {
		return nil, errSearchResultCleanupUnknown
	}
	sum := sha256.Sum256(data)
	if err != nil || len(data) == 0 || len(data) > maxConfigBytes || hex.EncodeToString(sum[:]) != r.digest {
		return nil, errors.New("search_result_unavailable")
	}
	var config map[string]json.RawMessage
	if json.Unmarshal(data, &config) != nil || config == nil || config["vpnSweepSelection"] != nil {
		return nil, errors.New("search_result_unavailable")
	}
	selection := make(map[string]any)
	for _, name := range []string{"fragment", "packets", "length", "interval", "maxSplit", "mux", "tcpConcurrency", "xudpConcurrency", "quic", "fingerprint", "noise", "noises"} {
		if value, ok := r.selection[name]; ok {
			selection[name] = value
		}
	}
	encoded, err := json.Marshal(selection)
	if err != nil {
		return nil, errors.New("search_result_unavailable")
	}
	config["vpnSweepSelection"] = encoded
	augmented, err := json.Marshal(config)
	if err != nil || len(augmented) > maxConfigBytes {
		return nil, errors.New("search_result_unavailable")
	}
	augmentedSum := sha256.Sum256(augmented)
	return &SearchVerifiedResult{file: io.NopCloser(bytes.NewReader(augmented)), digest: hex.EncodeToString(augmentedSum[:])}, nil
}

func (r *SearchVerifiedResult) Digest() string { return r.digest }

// ReadChunk returns an empty slice at EOF and never returns more than 16 MiB
// in total, including if the file grows after the initial integrity check.
func (r *SearchVerifiedResult) ReadChunk(maxBytes int64) ([]byte, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed || r.file == nil || r.readErr != nil {
		if r.readErr != nil {
			return nil, r.readErr
		}
		return nil, errors.New("search_result_closed")
	}
	if maxBytes < 1 || maxBytes > maxSearchResultChunkBytes {
		return nil, errors.New("search_result_invalid_chunk")
	}
	remaining := maxSearchResultBytes - r.read
	if remaining == 0 {
		var extra [1]byte
		n, err := r.file.Read(extra[:])
		if n > 0 {
			r.readErr = errors.New("search_result_limit")
			return nil, r.readErr
		}
		if err != nil && err != io.EOF {
			r.readErr = errors.New("search_result_read_failed")
			return nil, r.readErr
		}
		return []byte{}, nil
	}
	if maxBytes > remaining {
		maxBytes = remaining
	}
	chunk := make([]byte, int(maxBytes))
	n, err := r.file.Read(chunk)
	if err != nil && err != io.EOF {
		r.readErr = errors.New("search_result_read_failed")
		return nil, r.readErr
	}
	r.read += int64(n)
	return chunk[:n], nil
}

func (r *SearchVerifiedResult) Close() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !r.closed {
		r.closed = true
		if r.file == nil || r.file.Close() != nil {
			r.closeErr = errSearchResultCleanupUnknown
		}
	}
	return r.closeErr
}
