package model

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"time"
)

const SchemaVersion = 1
const SearchVersion = 2
const TargetCommit = "ac5d8a83ba798c390cd89f235c3d4c35c77c1586"

type Error struct {
	Code      string `json:"code"`
	Phase     string `json:"phase"`
	Retryable bool   `json:"retryable"`
}

func (e *Error) Error() string     { return e.Phase + ": " + e.Code }
func Err(code, phase string) error { return &Error{Code: code, Phase: phase} }
func Code(err error) string {
	var e *Error
	if errors.As(err, &e) {
		return e.Code
	}
	return "internal_error"
}

type Profile struct {
	Outbound map[string]any `json:"outbound"`
}
type Candidate struct {
	Profile int            `json:"profile"`
	Params  map[string]any `json:"params"`
	ID      string         `json:"id"`
}
type CoreIdentity struct {
	Commit string `json:"commit"`
	SHA256 string `json:"sha256"`
}
type Probe struct {
	ID             string   `json:"id"`
	Operator       string   `json:"operator"`
	URL            string   `json:"url"`
	Kind           string   `json:"kind"`
	ContainsAll    []string `json:"contains_all,omitempty"`
	MinBytes       int64    `json:"min_bytes,omitempty"`
	Contains       string   `json:"contains,omitempty"`
	SHA256         string   `json:"sha256,omitempty"`
	Bytes          int64    `json:"bytes,omitempty"`
	MIME           string   `json:"mime,omitempty"`
	Status         int      `json:"status"`
	RedirectHosts  []string `json:"redirect_hosts,omitempty"`
	TimeoutSeconds int      `json:"timeout_seconds"`
}
type Policy struct {
	Video            *VideoPolicy `json:"video,omitempty"`
	Version          string       `json:"version"`
	Rounds           int          `json:"rounds"`
	MinOperators     int          `json:"min_operators"`
	Downloads        int          `json:"downloads"`
	MinDownloadBytes int64        `json:"min_download_bytes"`
	RequireUDP       bool         `json:"require_udp"`
	RequireQUIC      bool         `json:"require_quic"`
	Probes           []Probe      `json:"probes"`
	UDP              []UDPProbe   `json:"udp,omitempty"`
}

type UDPProbe struct {
	ID             string `json:"id"`
	Address        string `json:"address"`
	TimeoutSeconds int    `json:"timeout_seconds"`
	Kind           string `json:"kind,omitempty"`
	NoiseOnly      bool   `json:"noise_only,omitempty"`
}
type ProbeResult struct {
	Media           *MediaEvidence `json:"media,omitempty"`
	ID              string         `json:"id"`
	Probe           string         `json:"probe"`
	Round           int            `json:"round"`
	Transport       string         `json:"transport"`
	Outcome         string         `json:"outcome"`
	Phase           string         `json:"phase"`
	HTTPStatus      int            `json:"http_status,omitempty"`
	Bytes           int64          `json:"bytes"`
	DurationMS      int64          `json:"duration_ms"`
	ContentVerified bool           `json:"content_verified,omitempty"`
	MIME            string         `json:"mime,omitempty"`
	TLSVerified     bool           `json:"tls_verified"`
	Redirects       int            `json:"redirects"`
}
type Attempt struct {
	ID        string        `json:"id"`
	Candidate Candidate     `json:"candidate"`
	Started   time.Time     `json:"started"`
	Outcome   string        `json:"outcome"`
	Results   []ProbeResult `json:"results,omitempty"`
	Restarts  int           `json:"restarts"`
}
type Budgets struct {
	SessionSeconds int    `json:"session_seconds"`
	PayloadBytes   int64  `json:"payload_bytes"`
	Attempts       uint64 `json:"attempts"`
	JournalBytes   int64  `json:"journal_bytes"`
	AttemptSeconds int    `json:"attempt_seconds"`
	StartupSeconds int    `json:"startup_seconds"`
	CleanupSeconds int    `json:"cleanup_seconds"`
	IntervalMS     int    `json:"interval_ms"`
}

func DefaultBudgets() Budgets {
	return Budgets{SessionSeconds: 7200, PayloadBytes: 256 << 20, JournalBytes: 256 << 20, AttemptSeconds: 600, StartupSeconds: 15, CleanupSeconds: 5, IntervalMS: 2000}
}

type Manifest struct {
	Resolver      *ResolverIdentity `json:"resolver,omitempty"`
	SearchVersion int               `json:"search_version,omitempty"`
	Version       int               `json:"version"`
	SeriesID      string            `json:"series_id"`
	Created       time.Time         `json:"created"`
	Mode          string            `json:"mode"`
	NetworkLabel  string            `json:"network_label"`
	Environment   string            `json:"environment"`
	Core          CoreIdentity      `json:"core"`
	InputID       string            `json:"input_id"`
	Space         json.RawMessage   `json:"space"`
	Policy        Policy            `json:"policy"`
	Budgets       Budgets           `json:"budgets"`
	TrustID       string            `json:"trust_id"`
}

func ID() string {
	var b [16]byte
	if _, err := io.ReadFull(rand.Reader, b[:]); err != nil {
		panic("secure random unavailable")
	}
	return hex.EncodeToString(b[:])
}
func Digest(v any) string {
	b, _ := json.Marshal(v)
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}
func PrivateID(key []byte, v any) string {
	b, _ := json.Marshal(v)
	h := hmac.New(sha256.New, key)
	_, _ = h.Write(b)
	return hex.EncodeToString(h.Sum(nil))
}

// Decode rejects unknown fields, trailing values and duplicate object keys.
func Decode(data []byte, v any) error {
	if err := uniqueKeys(data); err != nil {
		return Err("invalid_json", "input")
	}
	d := json.NewDecoder(bytesReader(data))
	d.DisallowUnknownFields()
	if err := d.Decode(v); err != nil {
		return Err("invalid_schema", "input")
	}
	if d.Decode(new(any)) != io.EOF {
		return Err("trailing_json", "input")
	}
	return nil
}

// Optional fields preserve the byte representation of legacy manifests/journal events.
type VideoPolicy struct {
	Required       []string          `json:"required"`
	Samples        int               `json:"samples"`
	MinMbps        float64           `json:"min_mbps"`
	MaxReadGapMS   int               `json:"max_read_gap_ms"`
	SampleBytes    int64             `json:"sample_bytes"`
	TimeoutSeconds int               `json:"timeout_seconds"`
	Reference      MediaReference    `json:"reference"`
	YouTube        *YouTubeReference `json:"youtube,omitempty"`
}
type MediaReference struct {
	URL        string `json:"url"`
	Bytes      int64  `json:"bytes"`
	SHA256     string `json:"sha256"`
	Provenance string `json:"provenance"`
	License    string `json:"license"`
	Status     string `json:"status"`
}
type YouTubeReference struct {
	VideoID    string `json:"video_id"`
	Permission string `json:"permission"`
	MinHeight  int    `json:"min_height"`
}
type ResolverIdentity struct {
	AdapterSHA256  string `json:"adapter_sha256"`
	PythonSHA256   string `json:"python_sha256"`
	JSSHA256       string `json:"js_sha256"`
	PythonVersion  string `json:"python_version"`
	JSVersion      string `json:"js_version"`
	YTDLP          string `json:"yt_dlp"`
	EJS            string `json:"ejs"`
	PackageSHA256  string `json:"package_sha256"`
	AdapterVersion int    `json:"adapter_version"`
}
type MediaSample struct {
	Bytes             int64  `json:"bytes"`
	DurationNS        int64  `json:"duration_ns"`
	MaxReadGapNS      int64  `json:"max_read_gap_ns"`
	SHA256            string `json:"sha256"`
	ReferenceVerified bool   `json:"reference_verified"`
	ContainerVerified bool   `json:"container_verified"`
	TLSVerified       bool   `json:"tls_verified"`
	Status            int    `json:"status"`
}
type MediaEvidence struct {
	Version    int           `json:"version"`
	Capability string        `json:"capability"`
	Samples    []MediaSample `json:"samples,omitempty"`
	FormatID   string        `json:"format_id,omitempty"`
	Height     int           `json:"height,omitempty"`
}
