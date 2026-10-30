package search

import (
	"context"
	"github.com/Seraf-seraf/vpn-sweep/internal/export"
	"github.com/Seraf-seraf/vpn-sweep/internal/media"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/policy"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
	"strings"
	"testing"
)

func TestBuiltinPagesRunTwiceWithoutVideo(t *testing.T) {
	for _, outcome := range []string{"pass", "page_failed"} {
		t.Run(outcome, func(t *testing.T) {
			m, profiles, g, s := setup(t)
			m.Policy = policy.Default()
			m.Environment, m.NetworkLabel = "unverified", ""
			m.Budgets.Attempts = 1
			opens, closed, pages, exports := 0, 0, 0, 0
			result := t.TempDir()
			e := Engine{Open: func(context.Context, model.Profile, map[string]any) (Tunnel, error) {
				opens++
				return tunnel{&closed}, nil
			}, Check: func(ctx context.Context, proxy socks.Endpoint, pr model.Probe, round int) model.ProbeResult {
				pages++
				r := pass(ctx, proxy, pr, round)
				r.Bytes, r.MIME, r.ContentVerified = pr.MinBytes, "text/html", true
				if outcome == "page_failed" {
					r.Outcome = "request_failed"
				}
				return r
			}, Media: func(context.Context, socks.Endpoint, model.Policy, int) []model.ProbeResult {
				t.Fatal("default policy started a video check")
				return nil
			}, Export: func(a model.Attempt) error {
				if closed != opens {
					t.Fatal("export before core cleanup")
				}
				exports++
				return export.Save(result, m, profiles, a)
			}}
			status, err := e.Run(context.Background(), m, profiles, make([]byte, 32), g, s)
			if err != nil || closed != opens || s.View().Pending != nil {
				t.Fatal(status, err, opens, closed)
			}
			if outcome == "pass" {
				if status != "success" || opens != 2 || pages != 4 || exports != 1 {
					t.Fatal(status, opens, pages, exports)
				}
				report, err := export.ReadReport(result)
				if err != nil || report.VerificationScope != "google_youtube_web_pages_and_udp_dns_for_noise_only" || len(report.Capabilities) != 0 {
					t.Fatal(report, err)
				}
			} else if status != "paused_budget" || exports != 0 {
				t.Fatal(status, exports)
			}
		})
	}
}

func videoPolicy() model.Policy {
	return model.Policy{Version: media.Version, Rounds: 2, Video: &model.VideoPolicy{Required: []string{media.Transfer}, Samples: 3, MinMbps: 10, MaxReadGapMS: 3000, SampleBytes: 1 << 20, TimeoutSeconds: 5, Reference: model.MediaReference{URL: "https://sample.invalid/video.mp4", Bytes: 1 << 20, SHA256: strings.Repeat("1", 64), Status: "verified_reference", License: "synthetic", Provenance: "synthetic"}}}
}
func mediaPass(p model.Policy, round int) []model.ProbeResult {
	samples := []model.MediaSample{}
	for i := 0; i < 3; i++ {
		samples = append(samples, model.MediaSample{Bytes: 1 << 20, DurationNS: 1000000, MaxReadGapNS: 1000, SHA256: p.Video.Reference.SHA256, ReferenceVerified: true, ContainerVerified: true, TLSVerified: true, Status: 200})
	}
	return []model.ProbeResult{{ID: model.ID(), Probe: media.Transfer, Round: round, Transport: "https-tcp", Outcome: "pass", TLSVerified: true, ContentVerified: true, Bytes: 3 << 20, Media: &model.MediaEvidence{Version: 1, Capability: media.Transfer, Samples: samples}}}
}
func TestMediaRestartStopResumeAndBlocked(t *testing.T) {
	for _, blocked := range []bool{false, true} {
		t.Run(map[bool]string{false: "success", true: "blocked"}[blocked], func(t *testing.T) {
			m, profiles, g, s := setup(t)
			m.Policy = videoPolicy()
			opens, closed, exports, calls := 0, 0, 0, 0
			ctx, cancel := context.WithCancel(context.Background())
			e := Engine{Open: func(context.Context, model.Profile, map[string]any) (Tunnel, error) {
				opens++
				return tunnel{&closed}, nil
			}, Media: func(ctx context.Context, _ socks.Endpoint, p model.Policy, round int) []model.ProbeResult {
				calls++
				cancel()
				return mediaPass(p, round)
			}, Export: func(model.Attempt) error { exports++; return nil }}
			status, err := e.Run(ctx, m, profiles, make([]byte, 32), g, s)
			if err != nil || status != "interrupted" || s.View().Retry == nil || closed != 1 {
				t.Fatal(status, err)
			}
			candidate := s.View().Retry.ID
			e.Media = func(_ context.Context, _ socks.Endpoint, p model.Policy, round int) []model.ProbeResult {
				calls++
				r := mediaPass(p, round)
				if blocked {
					r[0].Outcome = "reference_unverified"
				}
				return r
			}
			status, err = e.Run(context.Background(), m, profiles, make([]byte, 32), g, s)
			if err != nil {
				t.Fatal(err)
			}
			if blocked {
				if status != "capability_blocked" || exports != 0 || s.View().Retry == nil || s.Seen(candidate) {
					t.Fatal(status, s.View())
				}
			} else {
				if status != "success" || exports != 1 || opens != 3 || closed != 3 || calls != 3 || s.View().Winner.Candidate.ID != candidate {
					t.Fatal(status, opens, closed, calls)
				}
				e.Open = func(context.Context, model.Profile, map[string]any) (Tunnel, error) {
					t.Fatal("searched after winner")
					return nil, nil
				}
				status, err = e.Run(context.Background(), m, profiles, make([]byte, 32), g, s)
				if err != nil || status != "success" || exports != 2 {
					t.Fatal(status, err)
				}
			}
		})
	}
}
func TestMediaBudgetReservesWholeRound(t *testing.T) {
	m, p, g, s := setup(t)
	m.Policy = videoPolicy()
	m.Budgets.PayloadBytes = media.RoundBudget(m.Policy) - 1
	closed := 0
	e := Engine{Open: func(context.Context, model.Profile, map[string]any) (Tunnel, error) { return tunnel{&closed}, nil }, Media: func(context.Context, socks.Endpoint, model.Policy, int) []model.ProbeResult {
		t.Fatal("budget exceeded")
		return nil
	}}
	status, err := e.Run(context.Background(), m, p, make([]byte, 32), g, s)
	if err != nil || status != "paused_budget" || s.View().Retry == nil || closed != 1 {
		t.Fatal(status, err)
	}
}
