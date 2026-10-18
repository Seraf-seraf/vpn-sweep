package model

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
)

func bytesReader(b []byte) io.Reader { return bytes.NewReader(b) }
func uniqueKeys(b []byte) error {
	d := json.NewDecoder(bytes.NewReader(b))
	d.UseNumber()
	var value func() error
	value = func() error {
		t, e := d.Token()
		if e != nil {
			return e
		}
		if delim, ok := t.(json.Delim); ok {
			switch delim {
			case '{':
				seen := map[string]bool{}
				for d.More() {
					k, e := d.Token()
					if e != nil {
						return e
					}
					s, ok := k.(string)
					if !ok || seen[s] {
						return fmt.Errorf("duplicate key")
					}
					seen[s] = true
					if e = value(); e != nil {
						return e
					}
				}
				_, e = d.Token()
				return e
			case '[':
				for d.More() {
					if e = value(); e != nil {
						return e
					}
				}
				_, e = d.Token()
				return e
			default:
				return fmt.Errorf("invalid delimiter")
			}
		}
		return nil
	}
	if e := value(); e != nil {
		return e
	}
	if _, e := d.Token(); e != io.EOF {
		return fmt.Errorf("trailing JSON")
	}
	return nil
}
