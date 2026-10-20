package cli

import (
	"bytes"
	"github.com/Seraf-seraf/vpn-sweep/internal/search"
	"strings"
	"testing"
)

func TestProgressLogsFailuresWithoutUntrustedText(t *testing.T) {
	var out bytes.Buffer
	_, progress := progressLog(&out)
	for _, outcome := range []string{"tls_failure", "resolver_token_required", "core_start_failed", "secret-marker\nforged log line"} {
		progress(search.Progress{Event: "probe_finished", Attempt: 2, Round: 1, Probe: 3, Outcome: outcome, Bytes: 4096, DurationMS: 45})
	}
	progress(search.Progress{Event: "budget_reached", Outcome: "payload_budget"})
	for _, message := range []string{"Попытка 2, раунд 1, проверка 3", "4096 байт, 45 мс", "tls_failure", "resolver_token_required", "core_start_failed", "лимит полезных данных"} {
		if !strings.Contains(out.String(), message) {
			t.Fatalf("missing %q: %s", message, out.String())
		}
	}
	if strings.Contains(out.String(), "secret-marker") || strings.Contains(out.String(), "forged log line") {
		t.Fatal("untrusted outcome reached log")
	}
}
