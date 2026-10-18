package model

import (
	"bytes"
	"testing"
)

func TestStrictJSON(t *testing.T) {
	for _, s := range []string{`{"a":1,"a":2}`, `{"x":1}`, `{} {}`, `[]`} {
		var v struct {
			A int `json:"a"`
		}
		if Decode([]byte(s), &v) == nil {
			t.Fatalf("accepted invalid input")
		}
	}
}
func TestPrivateIdentity(t *testing.T) {
	a := PrivateID(bytes.Repeat([]byte{1}, 32), "secret")
	b := PrivateID(bytes.Repeat([]byte{2}, 32), "secret")
	if a == b || a == Digest("secret") {
		t.Fatal("identity not keyed")
	}
	if a != PrivateID(bytes.Repeat([]byte{1}, 32), "secret") {
		t.Fatal("unstable identity")
	}
}
