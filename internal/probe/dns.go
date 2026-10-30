package probe

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/binary"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
	"time"
)

var dnsQuestion = []byte{3, 'w', 'w', 'w', 6, 'g', 'o', 'o', 'g', 'l', 'e', 3, 'c', 'o', 'm', 0, 0, 1, 0, 1}

const MaxDNSResponseBytes = 512

func UDPDNS(ctx context.Context, proxy socks.Endpoint, id, address string, round int) model.ProbeResult {
	start := time.Now()
	result := model.ProbeResult{ID: model.ID(), Probe: id, Round: round, Transport: "udp-socks5", Phase: "udp"}
	query := make([]byte, 12, 12+len(dnsQuestion))
	if _, err := rand.Read(query[:2]); err != nil {
		result.Outcome = "udp_dns_random_failed"
		return result
	}
	query[2] = 1
	query[5] = 1
	query = append(query, dnsQuestion...)
	reply, err := proxy.UDPExchange(ctx, address, query, MaxDNSResponseBytes)
	result.Bytes = int64(len(reply))
	result.DurationMS = time.Since(start).Milliseconds()
	if err != nil {
		result.Outcome = model.Code(err)
	} else if !validDNSAnswer(query, reply) {
		result.Outcome = "udp_dns_invalid_response"
	} else {
		result.Outcome = "pass"
		result.Phase = "complete"
	}
	return result
}

func validDNSAnswer(query, reply []byte) bool {
	if len(query) < 12 || len(reply) < len(query) || !bytes.Equal(query[:2], reply[:2]) || !bytes.Equal(query[12:], reply[12:len(query)]) {
		return false
	}
	flags := binary.BigEndian.Uint16(reply[2:4])
	if flags&0x8000 == 0 || flags&0x7800 != 0 || flags&0x0200 != 0 || flags&0x000f != 0 || binary.BigEndian.Uint16(reply[4:6]) != 1 {
		return false
	}
	answers := int(binary.BigEndian.Uint16(reply[6:8]))
	if answers == 0 {
		return false
	}
	offset := len(query)
	foundA := false
	for i := 0; i < answers; i++ {
		var ok bool
		offset, ok = dnsNameEnd(reply, offset)
		if !ok || offset+10 > len(reply) {
			return false
		}
		typ := binary.BigEndian.Uint16(reply[offset : offset+2])
		class := binary.BigEndian.Uint16(reply[offset+2 : offset+4])
		length := int(binary.BigEndian.Uint16(reply[offset+8 : offset+10]))
		offset += 10
		if length > len(reply)-offset {
			return false
		}
		if typ == 1 && class == 1 && length == 4 {
			foundA = true
		}
		offset += length
	}
	return foundA
}

func dnsNameEnd(message []byte, offset int) (int, bool) {
	end := -1
	length := 0
	for steps := 0; steps < 128; steps++ {
		if offset < 12 || offset >= len(message) {
			return 0, false
		}
		label := int(message[offset])
		if label == 0 {
			if end < 0 {
				end = offset + 1
			}
			return end, true
		}
		if label&0xc0 == 0xc0 {
			if offset+1 >= len(message) {
				return 0, false
			}
			target := (label&0x3f)<<8 | int(message[offset+1])
			if target < 12 || target >= offset {
				return 0, false
			}
			if end < 0 {
				end = offset + 2
			}
			offset = target
			continue
		}
		if label&0xc0 != 0 || offset+1+label > len(message) {
			return 0, false
		}
		length += label + 1
		if length > 255 {
			return 0, false
		}
		offset += label + 1
	}
	return 0, false
}
