package model

import (
	"bytes"
	"encoding/json"
	"testing"
)

func TestLegacyOptionalMediaFieldsStayAbsent(t *testing.T) {
	for _, v := range []any{Policy{Version: "custom-v1"}, ProbeResult{Probe: "legacy"}, Manifest{Version: 1}} {
		b, e := json.Marshal(v)
		if e != nil {
			t.Fatal(e)
		}
		for _, field := range [][]byte{[]byte(`"video"`), []byte(`"media"`), []byte(`"resolver"`)} {
			if bytes.Contains(b, field) {
				t.Fatal("legacy encoding changed", string(b))
			}
		}
	}
}
