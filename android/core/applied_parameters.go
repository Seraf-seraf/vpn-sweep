package mobile

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/xtls/xray-core/app/proxyman"
	"github.com/xtls/xray-core/infra/conf/serial"
	"github.com/xtls/xray-core/proxy/freedom"
)

type displayParameter struct {
	Value string `json:"value"`
	State string `json:"state"`
}

// AppliedParametersJSON projects only non-secret settings from the same validated
// outbound graph used by Start. It never returns endpoint or credential fields.
func AppliedParametersJSON(source []byte) ([]byte, error) {
	prepared, err := PrepareConfig(source)
	if err != nil {
		return nil, err
	}
	config, err := serial.LoadJSONConfig(bytes.NewReader(prepared))
	if err != nil {
		return nil, errors.New("invalid_config")
	}
	values := map[string]displayParameter{}
	for _, key := range []string{"fragment", "packets", "length", "interval", "maxSplit", "mux", "tcpConcurrency", "xudpConcurrency", "quic", "fingerprint", "noise", "noises"} {
		values[key] = displayParameter{State: "not_applicable"}
	}
	set := func(key string, value any) { values[key] = displayParameter{Value: fmt.Sprint(value), State: "value"} }
	set("fragment", false)
	set("noise", false)
	set("mux", false)
	candidate := config.Outbound[0]
	senderValue, err := candidate.SenderSettings.GetInstance()
	if err != nil {
		return nil, errors.New("invalid_config")
	}
	sender, ok := senderValue.(*proxyman.SenderConfig)
	if !ok {
		return nil, errors.New("invalid_config")
	}
	if mux := sender.MultiplexSettings; mux != nil {
		set("mux", mux.Enabled)
		concurrency := mux.Concurrency
		if concurrency == 0 {
			concurrency = 8
		}
		set("tcpConcurrency", concurrency)
		set("xudpConcurrency", mux.XudpConcurrency)
		set("quic", mux.XudpProxyUDP443)
		if mux.Concurrency == 0 {
			value := values["tcpConcurrency"]
			value.State = "default"
			values["tcpConcurrency"] = value
		}
	}
	stream := sender.StreamSettings
	if stream != nil {
		for _, security := range stream.SecuritySettings {
			settings, e := security.GetInstance()
			if e != nil {
				return nil, errors.New("invalid_config")
			}
			if fingerprint, ok := settings.(interface{ GetFingerprint() string }); ok {
				if fingerprint.GetFingerprint() == "" {
					values["fingerprint"] = displayParameter{Value: "chrome", State: "default"}
				} else {
					set("fingerprint", fingerprint.GetFingerprint())
				}
			}
		}
	}
	// Follow only dialerProxy references reachable from the candidate.
	seen := map[string]bool{}
	current := candidate
	for {
		if seen[current.Tag] {
			return nil, errors.New("invalid_config")
		}
		seen[current.Tag] = true
		proxy, e := current.ProxySettings.GetInstance()
		if e != nil {
			return nil, errors.New("invalid_config")
		}
		if freedomConfig, ok := proxy.(*freedom.Config); ok {
			if fragment := freedomConfig.Fragment; fragment != nil {
				set("fragment", true)
				packets := fmt.Sprintf("%d-%d", fragment.PacketsFrom, fragment.PacketsTo)
				if fragment.PacketsFrom == 0 && fragment.PacketsTo == 1 {
					packets = "tlshello"
				}
				if fragment.PacketsFrom == 0 && fragment.PacketsTo == 0 {
					packets = "all"
				}
				set("packets", packets)
				set("length", displayRange(fragment.LengthMin, fragment.LengthMax))
				set("interval", displayRange(fragment.IntervalMin, fragment.IntervalMax))
				if fragment.MaxSplitMin == 0 && fragment.MaxSplitMax == 0 {
					values["maxSplit"] = displayParameter{State: "default"}
				} else {
					set("maxSplit", displayRange(fragment.MaxSplitMin, fragment.MaxSplitMax))
				}
			}
			set("noise", len(freedomConfig.Noises) > 0)
			set("noises", len(freedomConfig.Noises))
		}
		rawSender, e := current.SenderSettings.GetInstance()
		if e != nil {
			return nil, errors.New("invalid_config")
		}
		currentSender, ok := rawSender.(*proxyman.SenderConfig)
		if !ok {
			return nil, errors.New("invalid_config")
		}
		tag := currentSender.GetStreamSettings().GetSocketSettings().GetDialerProxy()
		if tag == "" {
			break
		}
		found := false
		for _, outbound := range config.Outbound {
			if outbound.Tag == tag {
				current = outbound
				found = true
				break
			}
		}
		if !found {
			return nil, errors.New("invalid_config")
		}
	}
	return json.Marshal(struct {
		Version int                         `json:"version"`
		Values  map[string]displayParameter `json:"values"`
	}{1, values})
}

func displayRange(first, last uint64) string {
	if first == last {
		return fmt.Sprint(first)
	}
	return fmt.Sprintf("%d-%d", first, last)
}
