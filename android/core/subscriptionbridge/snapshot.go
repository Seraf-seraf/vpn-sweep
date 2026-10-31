package subscriptionbridge

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"sync"

	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/subscription"
	"golang.org/x/sys/unix"
)

const (
	StatusHasSupported    int64 = 1
	StatusNoSupported     int64 = 2
	maxRecords                  = 10000
	maxSelectionJSONBytes       = 2 + maxRecords*35
	snapshotHeaderBytes         = 44
	snapshotRecordBytes         = 49
	maxSnapshotBytes            = snapshotHeaderBytes + importer.MaxInputBytes + maxRecords*snapshotRecordBytes
)

var (
	errSnapshot = errors.New("subscription_snapshot_invalid")
	errSize     = errors.New("subscription_size")
	errInput    = errors.New("subscription_input_invalid")
	errClosed   = errors.New("subscription_closed")
	errIO       = errors.New("subscription_io")
)

type Snapshot struct {
	mu        sync.Mutex
	raw       []byte
	batch     subscription.Batch
	stableIDs []string
	status    int64
	closed    bool
}

func (*Snapshot) String() string   { return "subscription snapshot" }
func (*Snapshot) GoString() string { return "subscription snapshot" }

func parseSnapshot(ctx context.Context, raw []byte) (*Snapshot, error) {
	limits := subscription.DecodeLimits{
		MaxInputBytes: importer.MaxInputBytes, MaxDecodedBytes: importer.MaxInputBytes,
		MaxRecordBytes: importer.MaxInputBytes, MaxRecords: maxRecords,
	}
	batch, err := subscription.ParseSubscription(ctx, raw, limits)
	status := StatusHasSupported
	if err != nil {
		if model.Code(err) != "no_supported_profiles" {
			return nil, safeError(err)
		}
		status = StatusNoSupported
	}
	ids := make([]string, len(batch.Records))
	for i, record := range batch.Records {
		ids[i] = record.ID
	}
	return &Snapshot{raw: bytes.Clone(raw), batch: batch, stableIDs: ids, status: status}, nil
}

func (s *Snapshot) Status() int64 {
	if s == nil {
		return 0
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return 0
	}
	return s.status
}

func (s *Snapshot) RecordCount() int64 {
	if s == nil {
		return 0
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return 0
	}
	return int64(len(s.batch.Records))
}

func (s *Snapshot) RecordJSON(index int64) ([]byte, error) {
	if s == nil {
		return nil, errClosed
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil, errClosed
	}
	if index < 0 || index >= int64(len(s.batch.Records)) {
		return nil, errInput
	}
	record := s.batch.Records[index]
	record.ID = s.stableIDs[index]
	b, err := json.Marshal(record)
	if err != nil {
		return nil, errSnapshot
	}
	return b, nil
}

func fingerprint(b subscription.Batch, r subscription.Record) ([32]byte, error) {
	privateID := r.ID
	r.ID = ""
	v := struct {
		Record  subscription.Record `json:"record"`
		Profile *model.Profile      `json:"profile,omitempty"`
	}{Record: r}
	if r.Supported {
		p, ok := b.Profile(privateID)
		if !ok {
			return [32]byte{}, errSnapshot
		}
		v.Profile = &p
	}
	encoded, err := json.Marshal(v)
	if err != nil {
		return [32]byte{}, errSnapshot
	}
	return sha256.Sum256(encoded), nil
}

func (s *Snapshot) WriteSnapshot(fd int64) (int64, error) {
	if s == nil {
		return 0, errClosed
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return 0, errClosed
	}
	payload := make([]byte, 0, len(s.raw)+len(s.batch.Records)*snapshotRecordBytes)
	payload = append(payload, s.raw...)
	for i, r := range s.batch.Records {
		id, err := hex.DecodeString(s.stableIDs[i])
		if err != nil || len(id) != 16 {
			return 0, errSnapshot
		}
		sum, err := fingerprint(s.batch, r)
		if err != nil {
			return 0, err
		}
		payload = append(payload, id...)
		if r.Supported {
			payload = append(payload, 1)
		} else {
			payload = append(payload, 0)
		}
		payload = append(payload, sum[:]...)
	}
	data := make([]byte, snapshotHeaderBytes, snapshotHeaderBytes+len(payload))
	copy(data[:4], "SVS1")
	binary.BigEndian.PutUint32(data[4:8], uint32(len(s.raw)))
	binary.BigEndian.PutUint32(data[8:12], uint32(len(s.batch.Records)))
	sum := sha256.Sum256(payload)
	copy(data[12:44], sum[:])
	data = append(data, payload...)
	return writeFD(fd, data)
}

func ReadSnapshot(fd, length int64) (*Snapshot, error) {
	if length < snapshotHeaderBytes || length > maxSnapshotBytes {
		return nil, errSnapshot
	}
	f, err := dupRegularFD(fd)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || info.Size() != length {
		return nil, errSnapshot
	}
	data := make([]byte, int(length))
	if n, err := f.ReadAt(data, 0); err != nil && !(err == io.EOF && n == len(data)) {
		return nil, errSnapshot
	}
	if string(data[:4]) != "SVS1" {
		return nil, errSnapshot
	}
	rawLen := int(binary.BigEndian.Uint32(data[4:8]))
	count := int(binary.BigEndian.Uint32(data[8:12]))
	if rawLen <= 0 || rawLen > importer.MaxInputBytes || count <= 0 || count > maxRecords || snapshotHeaderBytes+rawLen+count*snapshotRecordBytes != len(data) {
		return nil, errSnapshot
	}
	sum := sha256.Sum256(data[snapshotHeaderBytes:])
	if !bytes.Equal(data[12:44], sum[:]) {
		return nil, errSnapshot
	}
	s, err := parseSnapshot(context.Background(), data[snapshotHeaderBytes:snapshotHeaderBytes+rawLen])
	if err != nil || len(s.batch.Records) != count {
		return nil, errSnapshot
	}
	seen := make(map[string]struct{}, count)
	offset := snapshotHeaderBytes + rawLen
	for i, r := range s.batch.Records {
		entry := data[offset : offset+snapshotRecordBytes]
		id := hex.EncodeToString(entry[:16])
		_, duplicate := seen[id]
		seen[id] = struct{}{}
		expected, err := fingerprint(s.batch, r)
		if duplicate || err != nil || entry[16] > 1 || (entry[16] == 1) != r.Supported || !bytes.Equal(expected[:], entry[17:]) {
			s.Close()
			return nil, errSnapshot
		}
		s.stableIDs[i] = id
		offset += snapshotRecordBytes
	}
	return s, nil
}

func (s *Snapshot) WriteSelected(idsJSON []byte, fd int64) (int64, error) {
	if s == nil {
		return 0, errClosed
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return 0, errClosed
	}
	var ids []string
	if len(idsJSON) > maxSelectionJSONBytes || json.Unmarshal(idsJSON, &ids) != nil || len(ids) == 0 || len(ids) > maxRecords {
		return 0, errInput
	}
	selected := make(map[string]struct{}, len(ids))
	for _, id := range ids {
		if _, exists := selected[id]; exists {
			return 0, errInput
		}
		selected[id] = struct{}{}
	}
	var out bytes.Buffer
	out.WriteByte('[')
	found := 0
	for i, r := range s.batch.Records {
		if _, ok := selected[s.stableIDs[i]]; !ok {
			continue
		}
		if !r.Supported {
			return 0, errInput
		}
		p, ok := s.batch.Profile(r.ID)
		if !ok {
			return 0, errSnapshot
		}
		encoded, err := json.Marshal(p)
		if err != nil {
			return 0, errSnapshot
		}
		extra := len(encoded) + 1
		if found != 0 {
			extra++
		}
		if len(encoded) > importer.MaxInputBytes || out.Len()+extra > importer.MaxInputBytes {
			return 0, errSize
		}
		if found != 0 {
			out.WriteByte(',')
		}
		out.Write(encoded)
		found++
	}
	if found != len(ids) {
		return 0, errInput
	}
	out.WriteByte(']')
	return writeFD(fd, out.Bytes())
}

func (s *Snapshot) Close() error {
	if s == nil {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	s.raw = nil
	s.batch = subscription.Batch{}
	s.stableIDs = nil
	return nil
}

func dupRegularFD(fd int64) (*os.File, error) {
	if fd < 0 || int64(int(fd)) != fd {
		return nil, errIO
	}
	copyFD, err := unix.Dup(int(fd))
	if err != nil {
		return nil, errIO
	}
	f := os.NewFile(uintptr(copyFD), "subscription-private-fd")
	if f == nil {
		unix.Close(copyFD)
		return nil, errIO
	}
	info, err := f.Stat()
	if err != nil || !info.Mode().IsRegular() {
		f.Close()
		return nil, errIO
	}
	return f, nil
}

func writeFD(fd int64, data []byte) (int64, error) {
	f, err := dupRegularFD(fd)
	if err != nil {
		return 0, err
	}
	defer f.Close()
	n, err := f.WriteAt(data, 0)
	if err != nil || n != len(data) || f.Truncate(int64(len(data))) != nil {
		return 0, errIO
	}
	return int64(n), nil
}

func safeError(err error) error {
	switch model.Code(err) {
	case "empty_input", "invalid_utf8", "invalid_envelope", "record_count", "record_too_large", "decoded_too_large", "input_too_large", "id_unavailable", "invalid_limits", "cancelled", "subscription_timeout", "invalid_subscription_url", "destination_rejected", "resolution_failed", "network_failed", "tls_failed", "redirect_rejected", "subscription_http_status", "unsupported_content_encoding", "subscription_read_failed", "subscription_too_large":
		return errors.New(model.Code(err))
	default:
		return errors.New("subscription_internal")
	}
}
