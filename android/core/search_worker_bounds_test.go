package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"math"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Seraf-seraf/vpn-sweep/android/core/searchbridge"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/policy"
	"github.com/Seraf-seraf/vpn-sweep/internal/space"
)

func TestSearchWorkerWireBoundsAreInclusiveAndChecked(t *testing.T) {
	if searchWorkerFrameHeaderBytes != 16 || searchWorkerMaxJSONLeafBytes != importer.MaxInputBytes ||
		searchWorkerMaxOpenFrameBytes != 16+2*int64(importer.MaxInputBytes) ||
		searchWorkerMaxCallFrameBytes != 16+int64(importer.MaxInputBytes) ||
		searchWorkerMaxReplyEnvelopeBytes != int64(importer.MaxInputBytes)+21 {
		t.Fatal("search worker limits diverged from the wire contract")
	}
	for _, length := range []int64{1, int64(importer.MaxInputBytes) - 1, int64(importer.MaxInputBytes)} {
		if !searchWorkerJSONWithinBounds(length) {
			t.Fatalf("JSON leaf %d should fit", length)
		}
	}
	if searchWorkerJSONWithinBounds(int64(importer.MaxInputBytes) + 1) {
		t.Fatal("oversized JSON leaf accepted")
	}
	if !searchWorkerFrameWithinBounds(searchWorkerMaxOpenFrameBytes, int64(importer.MaxInputBytes), int64(importer.MaxInputBytes)) {
		t.Fatal("maximum OPEN frame rejected")
	}
	if !searchWorkerFrameWithinBounds(searchWorkerMaxCallFrameBytes, int64(importer.MaxInputBytes)) {
		t.Fatal("maximum CALL frame rejected")
	}
	if searchWorkerFrameWithinBounds(searchWorkerMaxCallFrameBytes, int64(importer.MaxInputBytes)+1) {
		t.Fatal("oversized CALL frame accepted")
	}
	if !searchWorkerReplyWithinBounds(searchWorkerMaxReplyEnvelopeBytes) ||
		searchWorkerReplyWithinBounds(searchWorkerMaxReplyEnvelopeBytes+1) {
		t.Fatal("reply envelope boundary mismatch")
	}

	if got, ok := checkedSearchWorkerFramePart(math.MaxInt64-1, 1); !ok || got != math.MaxInt64 {
		t.Fatal("checked addition rejected the exact int64 maximum")
	}
	if _, ok := checkedSearchWorkerFramePart(math.MaxInt64-1, 2); ok {
		t.Fatal("checked addition overflow accepted")
	}
}

func TestAndroidSearchRejectsOversizedSerializedPolicyBeforeWorkerOpen(t *testing.T) {
	value := policy.Default()
	value.Probes[0].ID = strings.Repeat("<", importer.MaxInputBytes/6+1)
	encoded, err := json.Marshal(value.Probes[0])
	if err != nil {
		t.Fatal(err)
	}
	if len(encoded) <= importer.MaxInputBytes {
		t.Fatal("fixture did not exceed the encoded JSON limit")
	}
	if searchWorkerPolicyRequestWithinBounds(value) {
		t.Fatal("oversized serialized policy accepted")
	}

	input, err := importer.Parse([]byte("socks5://synthetic.invalid:1080"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	profileBytes, err := json.Marshal(input.Profiles)
	if err != nil {
		t.Fatal(err)
	}
	spaceBytes, err := json.Marshal(space.Spec{Version: 1, Strategy: "exhaustive"})
	if err != nil {
		t.Fatal(err)
	}
	manifest := model.Manifest{
		Version: model.SchemaVersion, SearchVersion: model.SearchVersion, SeriesID: "synthetic",
		Mode: "core", Environment: "unverified", Core: model.CoreIdentity{Commit: model.TargetCommit}, Space: spaceBytes,
		Budgets: model.DefaultBudgets(), Policy: model.Policy{
			Version: "custom-v1", Rounds: 2, MinOperators: 1, Downloads: 1, MinDownloadBytes: 1,
			Probes: []model.Probe{
				{ID: strings.Repeat("<", importer.MaxInputBytes/6+1), Operator: "a", URL: "https://a.invalid", Kind: "page", Contains: "expected", Status: 200, TimeoutSeconds: 1},
				{ID: "data", Operator: "b", URL: "https://b.invalid", Kind: "download", SHA256: strings.Repeat("0", 64), Bytes: 1, Status: 200, TimeoutSeconds: 1},
			},
		},
	}
	manifestBytes, err := json.Marshal(manifest)
	if err != nil {
		t.Fatal(err)
	}
	worker := &fakeEngineWorker{}
	root := t.TempDir()
	if _, err = NewSearchSession(
		42, manifestBytes, profileBytes, filepath.Join(root, "state"), filepath.Join(root, "staging"), worker,
	); err == nil || err.Error() != "search_worker_request_size" {
		t.Fatalf("got %v, want search_worker_request_size", err)
	}
	if worker.opens != 0 {
		t.Fatalf("worker opened before policy bound validation: opens=%d", worker.opens)
	}
}

func TestAndroidSearchRoundRejectsOversizedEncodedRequestBeforeWorkerCall(t *testing.T) {
	worker := &fakeEngineWorker{}
	round := &androidSearchRound{callback: worker, generation: 1}
	request := model.Probe{ID: strings.Repeat("<", importer.MaxInputBytes/6+1)}
	encoded, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	if len(encoded) <= importer.MaxInputBytes {
		t.Fatal("fixture did not exceed the encoded JSON limit")
	}
	var response probeReply
	err = round.call(context.Background(), SearchWorkerCheck, request, 0, &response)
	if !errors.Is(err, searchbridge.ErrCapabilityUnavailable) {
		t.Fatalf("got %v, want capability rejection", err)
	}
	worker.mu.Lock()
	calls := worker.checks
	worker.mu.Unlock()
	if calls != 0 {
		t.Fatalf("oversized request reached worker: calls=%d", calls)
	}
}

type oversizedReplyWorker struct {
	fakeEngineWorker
	reply []byte
}

func (w *oversizedReplyWorker) Call(int64, int, []byte, int, int64) []byte {
	w.mu.Lock()
	w.checks++
	w.mu.Unlock()
	return w.reply
}

func TestAndroidSearchRoundRejectsOversizedReplyEnvelope(t *testing.T) {
	worker := &oversizedReplyWorker{reply: bytes.Repeat([]byte{'x'}, int(searchWorkerMaxReplyEnvelopeBytes+1))}
	round := &androidSearchRound{callback: worker, generation: 1}
	var response probeReply
	err := round.call(context.Background(), SearchWorkerCheck, model.Probe{ID: "synthetic"}, 0, &response)
	if !errors.Is(err, searchbridge.ErrWorkerUnavailable) {
		t.Fatalf("got %v, want bounded worker reply rejection", err)
	}
	worker.mu.Lock()
	calls := worker.checks
	worker.mu.Unlock()
	if calls != 1 {
		t.Fatalf("worker callback count=%d, want one", calls)
	}
}

func TestAndroidSearchWorkerEncoderRejectsOversizedRawReply(t *testing.T) {
	result := model.ProbeResult{ID: "synthetic", Probe: strings.Repeat("x", importer.MaxInputBytes)}
	if _, err := encodeProbeResult(result); err == nil || err.Error() != "search_worker_reply_size" {
		t.Fatalf("got %v, want search_worker_reply_size", err)
	}
}
