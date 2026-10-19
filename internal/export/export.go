package export

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/media"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/policy"
	"github.com/Seraf-seraf/vpn-sweep/internal/private"
	"os"
	"path/filepath"
)

type Report struct {
	Capabilities      map[string]string   `json:"capabilities,omitempty"`
	VerificationScope string              `json:"verification_scope,omitempty"`
	PresetStatus      string              `json:"preset_status,omitempty"`
	Version           int                 `json:"version"`
	SeriesID          string              `json:"series_id"`
	CandidateID       string              `json:"candidate_id"`
	AttemptID         string              `json:"attempt_id"`
	Status            string              `json:"status"`
	Policy            string              `json:"policy"`
	Core              model.CoreIdentity  `json:"core"`
	Environment       string              `json:"environment"`
	Results           []model.ProbeResult `json:"results"`
	Limitations       []string            `json:"limitations"`
}

type completion struct {
	Version            int    `json:"version"`
	SeriesID           string `json:"series_id"`
	CandidateID        string `json:"candidate_id"`
	ConfigSHA256       string `json:"config_sha256"`
	ReportSHA256       string `json:"report_sha256"`
	VerificationSHA256 string `json:"verification_sha256"`
}

func verify(dir string, c completion) error {
	for name, want := range map[string]string{"working.json": c.ConfigSHA256, "report.json": c.ReportSHA256, "verification.json": c.VerificationSHA256} {
		b, e := private.Read(filepath.Join(dir, name), 16<<20)
		if e != nil {
			return e
		}
		h := sha256.Sum256(b)
		if hex.EncodeToString(h[:]) != want {
			return model.Err("export_integrity_failed", "export")
		}
	}
	return nil
}

func SafeDirectory(path string) (string, error) {
	d, e := private.Dir(path)
	if e != nil {
		return "", e
	}
	real, e := filepath.EvalSymlinks(d)
	if e != nil {
		return "", model.Err("output_unavailable", "export")
	}
	for p := real; ; p = filepath.Dir(p) {
		if _, e := os.Lstat(filepath.Join(p, ".git")); e == nil {
			return "", model.Err("output_inside_checkout", "export")
		}
		if filepath.Dir(p) == p {
			break
		}
	}
	return real, nil
}
func Save(dir string, m model.Manifest, profiles []model.Profile, a model.Attempt) error {
	if a.Candidate.Profile < 0 || a.Candidate.Profile >= len(profiles) || policy.Evaluate(m.Policy, a) != "pass" {
		return model.Err("unverified_result", "export")
	}
	d, e := SafeDirectory(dir)
	if e != nil {
		return e
	}
	if _, err := os.Lstat(filepath.Join(d, "COMPLETE.json")); err == nil {
		b, e := private.Read(filepath.Join(d, "COMPLETE.json"), 4096)
		if e != nil {
			return e
		}
		var c completion
		if model.Decode(b, &c) != nil || c.Version != 1 || c.SeriesID != m.SeriesID || c.CandidateID != a.Candidate.ID {
			return model.Err("export_conflict", "export")
		}
		return verify(d, c)
	} else if !os.IsNotExist(err) {
		return model.Err("export_unavailable", "export")
	}
	report := Report{VerificationScope: policy.Scope(m.Policy), PresetStatus: policy.PresetStatus(m.Policy), Version: 1, SeriesID: m.SeriesID, CandidateID: a.Candidate.ID, AttemptID: a.ID, Status: "policy_satisfied", Policy: m.Policy.Version, Core: m.Core, Environment: m.Environment, Results: a.Results, Limitations: []string{"time_and_network_specific", "no_system_android_vpn_claim", "happ_unverified", "android_device_unverified", "quic_unsupported", "media_not_tested"}}
	if m.Policy.Version == policy.GoogleYouTubeWeb {
		report.Limitations = append(report.Limitations, "google_and_youtube_pages_only", "single_operator_google", "not_general_internet", "no_bulk_download_test", "preset_live_unverified")
	}
	if m.Policy.Version == media.Version {
		report.Capabilities = media.Capabilities(m.Policy, a)
		report.Limitations = []string{"time_and_network_specific", "no_playback_decode_audio_or_drm_claim", "not_general_internet", "android_device_unverified", "happ_unverified", "quic_unsupported", "no_system_android_vpn_claim"}
	}
	cfg, e := capability.Render(profiles[a.Candidate.Profile], a.Candidate.Params, "127.0.0.1:10808", model.ID(), model.ID())
	if e != nil {
		return e
	}
	sum := sha256.Sum256(cfg)
	reportBytes, e := json.MarshalIndent(report, "", "  ")
	if e != nil {
		return model.Err("export_serialization", "export")
	}
	rh := sha256.Sum256(reportBytes)
	verificationBytes, e := json.MarshalIndent(m, "", "  ")
	if e != nil {
		return model.Err("export_serialization", "export")
	}
	vh := sha256.Sum256(verificationBytes)
	marker := completion{Version: 1, SeriesID: m.SeriesID, CandidateID: a.Candidate.ID, ConfigSHA256: hex.EncodeToString(sum[:]), ReportSHA256: hex.EncodeToString(rh[:]), VerificationSHA256: hex.EncodeToString(vh[:])}
	// COMPLETE is written last. Readers require it, so partial multi-file exports are never success.
	if e = private.Atomic(filepath.Join(d, "working.json"), cfg); e != nil {
		return e
	}
	if e = private.Atomic(filepath.Join(d, "report.json"), reportBytes); e != nil {
		return e
	}
	if e = private.Atomic(filepath.Join(d, "verification.json"), verificationBytes); e != nil {
		return e
	}
	if e = private.JSON(filepath.Join(d, "COMPLETE.json"), marker); e != nil {
		return e
	}
	return verify(d, marker)
}
func ReadReport(dir string) (Report, error) {
	var r Report
	b, e := private.Read(filepath.Join(dir, "report.json"), 4<<20)
	if e != nil {
		return r, e
	}
	e = json.Unmarshal(b, &r)
	return r, e
}
