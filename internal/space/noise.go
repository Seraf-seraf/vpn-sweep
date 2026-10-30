package space

import (
	"encoding/base64"
	"encoding/hex"
	"reflect"
	"strings"
)

const noisePreset = "udp-noise-v1"
const noisePresetIP = "udp-noise-ip-v1"
const noisePresetIPv4 = "udp-noise-ipv4-v1"
const noisePresetIPv6 = "udp-noise-ipv6-v1"

var noiseRandomPackets = [...]string{"1", "50-150", "1200"}
var noiseFixedTypes = [...]string{"str", "hex", "base64"}
var noiseSizes = [...]int{1, 64, 1200}
var noiseContent = [...]string{"A", "Z"}
var noiseDelays = [...]string{"0", "10-16", "1000"}
var noiseFamilies = [...]string{"ip", "ipv4", "ipv6"}

func presetFamilies(preset string) ([]string, bool) {
	switch preset {
	case noisePreset:
		return noiseFamilies[:], true
	case noisePresetIP:
		return noiseFamilies[:1], true
	case noisePresetIPv4:
		return noiseFamilies[:2], true
	case noisePresetIPv6:
		return []string{"ip", "ipv6"}, true
	}
	return nil, false
}

func noiseCount(preset string) int64 {
	families, _ := presetFamilies(preset)
	variants := len(noiseRandomPackets) + len(noiseFixedTypes)*len(noiseSizes)*len(noiseContent)
	return int64(variants * len(noiseDelays) * len(families) * 4)
}

func noiseValue(preset string, index int64) []any {
	families, _ := presetFamilies(preset)
	count := int(index%4) + 1
	index /= 4
	family := families[index%int64(len(families))]
	index /= int64(len(families))
	delay := noiseDelays[index%int64(len(noiseDelays))]
	index /= int64(len(noiseDelays))
	typ, packet := "rand", ""
	if index < int64(len(noiseRandomPackets)) {
		packet = noiseRandomPackets[index]
	} else {
		index -= int64(len(noiseRandomPackets))
		variants := int64(len(noiseSizes) * len(noiseContent))
		typ = noiseFixedTypes[index/variants]
		index %= variants
		packet = strings.Repeat(noiseContent[index%int64(len(noiseContent))], noiseSizes[index/int64(len(noiseContent))])
		switch typ {
		case "hex":
			packet = hex.EncodeToString([]byte(packet))
		case "base64":
			packet = base64.StdEncoding.EncodeToString([]byte(packet))
		}
	}
	items := make([]any, count)
	for i := range items {
		items[i] = map[string]any{"type": typ, "packet": packet, "delay": delay, "applyTo": family}
	}
	return items
}

func noiseIndex(preset string, value any) (int64, bool) {
	items, ok := value.([]any)
	if !ok || len(items) < 1 || len(items) > 4 {
		return 0, false
	}
	first, ok := items[0].(map[string]any)
	if !ok || len(first) != 4 {
		return 0, false
	}
	for _, item := range items[1:] {
		if !reflect.DeepEqual(first, item) {
			return 0, false
		}
	}
	typ, _ := first["type"].(string)
	packet, ok := first["packet"].(string)
	if !ok {
		return 0, false
	}
	delay, ok := stringIndex(noiseDelays[:], first["delay"])
	if !ok {
		return 0, false
	}
	families, _ := presetFamilies(preset)
	family, ok := stringIndex(families, first["applyTo"])
	if !ok {
		return 0, false
	}
	var variant int
	if typ == "rand" {
		variant, ok = stringIndex(noiseRandomPackets[:], packet)
		if !ok {
			return 0, false
		}
	} else {
		typeIndex, found := stringIndex(noiseFixedTypes[:], typ)
		if !found {
			return 0, false
		}
		b := []byte(packet)
		var err error
		switch typ {
		case "hex":
			b, err = hex.DecodeString(packet)
		case "base64":
			b, err = base64.StdEncoding.DecodeString(packet)
		}
		if err != nil || len(b) == 0 {
			return 0, false
		}
		content, found := stringIndex(noiseContent[:], string(b[0]))
		if !found {
			return 0, false
		}
		size := -1
		for i, n := range noiseSizes {
			if len(b) == n {
				size = i
				break
			}
		}
		if size < 0 || string(b) != strings.Repeat(noiseContent[content], len(b)) {
			return 0, false
		}
		variant = len(noiseRandomPackets) + typeIndex*len(noiseSizes)*len(noiseContent) + size*len(noiseContent) + content
	}
	index := ((variant*len(noiseDelays)+delay)*len(families)+family)*4 + len(items) - 1
	return int64(index), true
}

func stringIndex(values []string, value any) (int, bool) {
	for i, v := range values {
		if value == v {
			return i, true
		}
	}
	return 0, false
}
