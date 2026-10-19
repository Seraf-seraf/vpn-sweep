package cli

import (
	"context"
	"crypto/x509"
	"encoding/json"
	"flag"
	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/core"
	"github.com/Seraf-seraf/vpn-sweep/internal/export"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/media"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/policy"
	"github.com/Seraf-seraf/vpn-sweep/internal/private"
	"github.com/Seraf-seraf/vpn-sweep/internal/probe"
	"github.com/Seraf-seraf/vpn-sweep/internal/search"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
	"github.com/Seraf-seraf/vpn-sweep/internal/space"
	"github.com/Seraf-seraf/vpn-sweep/internal/state"
	"github.com/Seraf-seraf/vpn-sweep/internal/subscription"
	"io"
	"path/filepath"
	"strings"
	"time"
)

type options struct {
	video                                                                                bool
	youtubePython, youtubeJS                                                             string
	input, format, subscription, mode, binary, policy, space, state, output, network, ca string
	attest, custom, unverifiedWeb                                                        bool
	budgets                                                                              model.Budgets
}

func flags(args []string) (options, error) {
	var o options
	fs := flag.NewFlagSet("vpn-sweep", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	fs.StringVar(&o.input, "input", "", "private local input path, or - for stdin")
	fs.StringVar(&o.format, "format", "auto", "auto/json/uri/base64")
	fs.StringVar(&o.subscription, "subscription-file", "", "private file containing an HTTPS subscription URL")
	fs.StringVar(&o.mode, "mode", "", "core or happ")
	fs.StringVar(&o.binary, "core", "", "explicit external Xray executable")
	fs.StringVar(&o.policy, "policy", "", "versioned JSON probe policy")
	fs.StringVar(&o.space, "space", "", "JSON search space (default: broad range-pair space)")
	fs.StringVar(&o.state, "state", "", "private series directory outside checkout")
	fs.StringVar(&o.output, "output", "", "private result directory outside checkout")
	fs.StringVar(&o.network, "network-label", "", "local label identifying current network conditions")
	fs.StringVar(&o.ca, "ca-file", "", "explicit PEM trust roots for controlled tests")
	fs.BoolVar(&o.attest, "attest-no-other-vpn", false, "attest Termux is on intended network without another VPN")
	fs.BoolVar(&o.unverifiedWeb, "allow-unverified-web-profile", false, "accept Google/YouTube web-only scope and live-unverified preset")
	fs.BoolVar(&o.custom, "allow-custom-policy", false, "explicitly accept custom-v1 verification scope")
	fs.BoolVar(&o.video, "allow-video-profile", false, "accept bounded media delivery scope, not playback")
	fs.StringVar(&o.youtubePython, "youtube-python", "", "absolute isolated Python containing pinned yt-dlp/EJS")
	fs.StringVar(&o.youtubeJS, "youtube-js", "", "absolute Node runtime for optional resolver")
	o.budgets = model.DefaultBudgets()
	fs.IntVar(&o.budgets.SessionSeconds, "session-seconds", 7200, "session wall budget; 0=unlimited")
	fs.Int64Var(&o.budgets.PayloadBytes, "payload-bytes", 256<<20, "session payload budget; 0=unlimited")
	fs.Uint64Var(&o.budgets.Attempts, "attempts", 0, "network attempts per session; 0=no cap")
	fs.Int64Var(&o.budgets.JournalBytes, "journal-bytes", 256<<20, "journal disk budget; 0=unlimited")
	fs.IntVar(&o.budgets.AttemptSeconds, "attempt-seconds", 600, "candidate deadline")
	fs.IntVar(&o.budgets.StartupSeconds, "startup-seconds", 15, "core startup deadline")
	fs.IntVar(&o.budgets.CleanupSeconds, "cleanup-seconds", 5, "TERM/KILL wait deadline")
	fs.IntVar(&o.budgets.IntervalMS, "interval-ms", 2000, "minimum interval between candidates")
	if fs.Parse(args) != nil || fs.NArg() != 0 {
		return o, model.Err("invalid_arguments", "cli")
	}
	if o.budgets.SessionSeconds < 0 || o.budgets.PayloadBytes < 0 || o.budgets.JournalBytes < 0 || o.budgets.AttemptSeconds < 1 || o.budgets.StartupSeconds < 1 || o.budgets.CleanupSeconds < 1 || o.budgets.IntervalMS < 0 {
		return o, model.Err("invalid_budget", "cli")
	}
	if o.budgets.SessionSeconds > 365*24*3600 || o.budgets.AttemptSeconds > 86400 || o.budgets.StartupSeconds > 300 || o.budgets.CleanupSeconds > 60 || o.budgets.IntervalMS > 60000 {
		return o, model.Err("budget_safety_bound", "cli")
	}
	return o, nil
}
func read(path string, max int64) ([]byte, error) { return private.Read(path, max) }
func loadInput(ctx context.Context, o options, in io.Reader) (importer.Result, error) {
	if o.subscription != "" {
		if o.input != "" {
			return importer.Result{}, model.Err("ambiguous_input", "cli")
		}
		b, e := read(o.subscription, 8192)
		if e != nil {
			return importer.Result{}, e
		}
		data, e := (subscription.Fetcher{}).Fetch(ctx, strings.TrimSpace(string(b)))
		if e != nil {
			return importer.Result{}, e
		}
		return importer.Parse(data, o.format)
	}
	var b []byte
	var e error
	if o.input == "-" {
		b, e = io.ReadAll(io.LimitReader(in, importer.MaxInputBytes+1))
	} else if o.input != "" {
		b, e = read(o.input, importer.MaxInputBytes)
	} else {
		return importer.Result{}, model.Err("missing_input", "cli")
	}
	if e != nil {
		return importer.Result{}, e
	}
	return importer.Parse(b, o.format)
}
func loadSpace(path string) (*space.Generator, error) {
	s := space.Default()
	if path != "" {
		s = space.Spec{}
		b, e := read(path, 1<<20)
		if e != nil {
			return nil, e
		}
		if e = model.Decode(b, &s); e != nil {
			return nil, e
		}
	}
	return space.New(s)
}
func emit(w io.Writer, v any) error { return json.NewEncoder(w).Encode(v) }
func Run(ctx context.Context, args []string, in io.Reader, out, errOut io.Writer) int {
	if len(args) == 0 || args[0] == "help" {
		io.WriteString(out, "vpn-sweep inspect|space|policy|resolver-info|run|resume|status|report|export|version\nUse --input private-file --format auto; run requires --mode --core --policy --state --output --network-label --attest-no-other-vpn. See docs/usage.md.\n")
		return 0
	}
	if args[0] == "version" {
		io.WriteString(out, "vpn-sweep representative-video-v1 schema=1 search=2 xray=ac5d8a8 android/arm64-unverified\n")
		return 0
	}
	o, e := flags(args[1:])
	if e == nil {
		e = dispatch(ctx, args[0], o, in, out)
	}
	if e != nil {
		emit(errOut, map[string]any{"error": model.Code(e), "phase": "operation_failed"})
		switch model.Code(e) {
		case "space_exhausted":
			return 3
		case "paused_budget":
			return 4
		case "interrupted":
			return 130
		}
		return 2
	}
	return 0
}
func dispatch(ctx context.Context, command string, o options, in io.Reader, out io.Writer) error {
	switch command {
	case "inspect":
		r, e := loadInput(ctx, o, in)
		if e != nil {
			return e
		}
		items := []map[string]any{}
		for i, p := range r.Profiles {
			items = append(items, map[string]any{"profile": i, "protocol": p.Outbound["protocol"], "core_contract": "source_validated_runtime_unverified", "happ": "mapping_unverified"})
		}
		return emit(out, map[string]any{"format": r.Format, "profiles": items, "notices": r.Notices})
	case "space":
		g, e := loadSpace(o.space)
		if e != nil {
			return e
		}
		domains := []map[string]any{}
		for _, d := range g.Spec.Parameters {
			domains = append(domains, map[string]any{"name": d.Name, "explicit_values": len(d.Values), "min": d.Min, "max": d.Max, "step": d.Step, "pair": d.Pair})
		}
		return emit(out, map[string]any{"count": g.Count().String(), "strategy": g.Spec.Strategy, "seed": g.Spec.Seed, "parameters": domains, "catalog": capability.Catalog(), "scope": "before_profile_applicability_filter"})
	case "resolver-info":
		r := media.Resolver{Python: o.youtubePython, JS: o.youtubeJS}
		id, e := r.Identify(ctx)
		if e != nil {
			return e
		}
		return emit(out, id)
	case "policy":
		b, e := read(o.policy, 1<<20)
		if e != nil {
			return e
		}
		var p model.Policy
		if e = model.Decode(b, &p); e != nil {
			return e
		}
		if e = policy.Validate(p); e != nil {
			return e
		}
		return emit(out, map[string]any{"status": "valid_local_structure_only", "policy": p.Version, "verification_scope": policy.Scope(p), "preset_status": policy.PresetStatus(p), "network_requests": false})
	case "run", "resume":
		return run(ctx, command, o, in, out)
	case "status", "report", "export":
		if o.state == "" {
			return model.Err("missing_state", "cli")
		}
		s, e := state.Open(o.state, 0)
		if e != nil {
			return e
		}
		defer s.Close()
		m, p, _, e := s.LoadManifest()
		if e != nil {
			return e
		}
		v := s.View()
		if command == "export" {
			if v.Winner == nil || o.output == "" {
				return model.Err("no_verified_result_or_output", "export")
			}
			if e = export.Save(o.output, m, p, *v.Winner); e != nil {
				return e
			}
			return emit(out, map[string]any{"status": "exported", "series_id": m.SeriesID})
		}
		if command == "report" {
			return s.History(out)
		}
		message := ""
		if v.Status == "space_exhausted" {
			message = "рабочее соединение не найдено в исследованном пространстве"
		}
		return emit(out, map[string]any{"series_id": m.SeriesID, "status": v.Status, "next": v.Next, "attempts": v.Attempts, "payload_bytes": v.PayloadBytes, "inconclusive": v.Inconclusive, "pending": v.Pending != nil, "retry": v.Retry != nil, "winner": v.Winner != nil, "message": message})
	default:
		return model.Err("unknown_command", "cli")
	}
}
func run(ctx context.Context, command string, o options, in io.Reader, out io.Writer) error {
	if o.state == "" || o.output == "" || o.binary == "" || o.network == "" || !o.attest {
		return model.Err("missing_run_prerequisite", "cli")
	}
	if command == "run" {
		if e := capability.Gate(o.mode, model.TargetCommit); e != nil {
			return e
		}
		if o.policy == "" {
			return model.Err("explicit_policy_required", "cli")
		}
	}
	if _, e := export.SafeDirectory(o.state); e != nil {
		return e
	}
	if _, e := export.SafeDirectory(o.output); e != nil {
		return e
	}
	binary, e := filepath.Abs(o.binary)
	if e != nil {
		return model.Err("core_unavailable", "cli")
	}
	id, e := core.Identify(ctx, binary)
	if e != nil {
		return e
	}
	s, e := state.Open(o.state, o.budgets.JournalBytes)
	if e != nil {
		return e
	}
	defer s.Close()
	var resolver *media.Resolver
	var m model.Manifest
	var profiles []model.Profile
	var key []byte
	var g *space.Generator
	var roots *x509.CertPool
	if command == "run" {
		r, e := loadInput(ctx, o, in)
		if e != nil {
			return e
		}
		profiles = r.Profiles
		g, e = loadSpace(o.space)
		if e != nil {
			return e
		}
		b, e := read(o.policy, 1<<20)
		if e != nil {
			return e
		}
		var pol model.Policy
		if e = model.Decode(b, &pol); e != nil {
			return e
		}
		if e = policy.Validate(pol); e != nil {
			return e
		}
		if pol.Version == "custom-v1" && !o.custom {
			return model.Err("custom_policy_requires_opt_in", "cli")
		}
		if pol.Version == policy.GoogleYouTubeWeb && !o.unverifiedWeb {
			return model.Err("unverified_web_profile_requires_opt_in", "cli")
		}
		key, e = state.IdentityKey(filepath.Dir(s.Dir))
		if e != nil {
			return e
		}
		raw, _ := json.Marshal(g.Spec)
		m = model.Manifest{Version: 1, SearchVersion: model.SearchVersion, SeriesID: model.ID(), Created: time.Now().UTC(), Mode: o.mode, NetworkLabel: model.PrivateID(key, o.network), Environment: "user_attested", Core: id, InputID: model.PrivateID(key, profiles), Space: raw, Policy: pol, Budgets: o.budgets, TrustID: "system"}
		if pol.Version == media.Version {
			if !o.video {
				return model.Err("video_profile_requires_opt_in", "cli")
			}
			if pol.Video.YouTube != nil {
				resolver = &media.Resolver{Python: o.youtubePython, JS: o.youtubeJS}
				rid, err := resolver.Identify(ctx)
				if err != nil {
					return err
				}
				resolver.Identity = rid
				m.Resolver = &rid
			}
		}
		if o.ca != "" {
			b, e = read(o.ca, 1<<20)
			if e != nil {
				return e
			}
			roots = x509.NewCertPool()
			if !roots.AppendCertsFromPEM(b) {
				return model.Err("invalid_trust_roots", "cli")
			}
			m.TrustID = model.Digest(string(b))
			if e = private.Atomic(filepath.Join(s.Dir, "trust.pem"), b); e != nil {
				return e
			}
		}
		if e = s.SaveManifest(m, profiles, key); e != nil {
			return e
		}
	} else {
		m, profiles, key, e = s.LoadManifest()
		if e != nil {
			return e
		}
		if o.input != "" || o.subscription != "" || o.policy != "" || o.space != "" || o.ca != "" {
			return model.Err("resume_input_changes_require_new_series", "cli")
		}
		if id != m.Core || model.PrivateID(key, o.network) != m.NetworkLabel || o.mode != "" && o.mode != m.Mode {
			return model.Err("series_conditions_changed", "cli")
		}
		var spec space.Spec
		if e = model.Decode(m.Space, &spec); e != nil {
			return e
		}
		g, e = space.New(spec)
		if e != nil {
			return e
		}
		if m.TrustID != "system" {
			b, e := read(filepath.Join(s.Dir, "trust.pem"), 1<<20)
			if e != nil || model.Digest(string(b)) != m.TrustID {
				return model.Err("trust_roots_changed", "cli")
			}
			roots = x509.NewCertPool()
			if !roots.AppendCertsFromPEM(b) {
				return model.Err("invalid_trust_roots", "cli")
			}
		}
		m.Budgets = o.budgets
	}
	if m.Policy.Version == policy.GoogleYouTubeWeb && !o.unverifiedWeb {
		return model.Err("unverified_web_profile_requires_opt_in", "cli")
	}
	if m.Policy.Version == media.Version {
		if !o.video {
			return model.Err("video_profile_requires_opt_in", "cli")
		}
		if m.Policy.Video.YouTube != nil {
			resolver = &media.Resolver{Python: o.youtubePython, JS: o.youtubeJS}
			rid, err := resolver.Identify(ctx)
			if err != nil {
				return err
			}
			if m.Resolver == nil || rid != *m.Resolver {
				return model.Err("resolver_identity_changed", "cli")
			}
			resolver.Identity = rid
		}
	} else if o.youtubePython != "" || o.youtubeJS != "" || o.video {
		return model.Err("video_options_require_video_profile", "cli")
	}
	if e = capability.Gate(m.Mode, m.Core.Commit); e != nil {
		return e
	}
	if e = emit(out, map[string]any{"event": "series_start", "series_id": m.SeriesID, "mode": m.Mode, "core": m.Core, "policy": m.Policy.Version, "verification_scope": policy.Scope(m.Policy), "preset_status": policy.PresetStatus(m.Policy), "budgets": m.Budgets, "space_count": g.Count().String(), "environment": "user_attested_not_independently_proven"}); e != nil {
		return e
	}
	runner := core.Runner{Binary: binary, Identity: id, Directory: filepath.Join(s.Dir, "runtime"), Startup: time.Duration(m.Budgets.StartupSeconds) * time.Second, Cleanup: time.Duration(m.Budgets.CleanupSeconds) * time.Second}
	client := probe.Client{Roots: roots}
	mediaClient := media.Client{Roots: roots, Resolver: resolver}
	engine := search.Engine{Media: mediaClient.Round, Open: func(ctx context.Context, p model.Profile, params map[string]any) (search.Tunnel, error) {
		addr, e := core.ReserveAddress()
		if e != nil {
			return nil, e
		}
		user, pass := model.ID(), model.ID()
		cfg, e := capability.Render(p, params, addr, user, pass)
		if e != nil {
			return nil, e
		}
		return runner.Start(ctx, cfg, socks.Endpoint{Address: addr, User: user, Pass: pass})
	}, Check: client.Check, UDP: probe.UDPEcho, Export: func(a model.Attempt) error { return export.Save(o.output, m, profiles, a) }}
	status, e := engine.Run(ctx, m, profiles, key, g, s)
	if e != nil {
		return e
	}
	if e = emit(out, map[string]any{"status": status, "series_id": m.SeriesID, "policy": m.Policy.Version, "verification_scope": policy.Scope(m.Policy), "preset_status": policy.PresetStatus(m.Policy), "attempts": s.View().Attempts, "device_acceptance": "unverified", "happ": "mapping_unverified"}); e != nil {
		return e
	}
	if status != "success" {
		return model.Err(status, "search")
	}
	return nil
}
