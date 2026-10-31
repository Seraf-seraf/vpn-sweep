package subscription

import (
	"net/url"
	"strconv"
	"strings"

	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

type fragmentOption struct {
	length   string
	interval string
	packets  string
	maxSplit *string
}

type functionalOptions struct {
	fragment       *fragmentOption
	muxConcurrency *int
}

func extractFunctionalOptions(raw string) (string, functionalOptions, bool) {
	var options functionalOptions
	beforeFragment, fragment, hasFragment := strings.Cut(raw, "#")
	base, query, hasQuery := strings.Cut(beforeFragment, "?")
	if !hasQuery {
		return raw, options, true
	}
	values, err := url.ParseQuery(query)
	if err != nil {
		return "", options, false
	}
	for _, key := range []string{"fragment", "mux", "muxConcurrency"} {
		if len(values[key]) > 1 {
			return "", options, false
		}
	}
	if vs, ok := values["fragment"]; ok {
		parts := strings.Split(vs[0], ",")
		if len(parts) != 3 && len(parts) != 4 {
			return "", options, false
		}
		for i, name := range []string{"length", "interval", "packets"} {
			if capability.ValidateValue(name, parts[i]) != nil {
				return "", options, false
			}
		}
		f := &fragmentOption{length: parts[0], interval: parts[1], packets: parts[2]}
		if len(parts) == 4 {
			if capability.ValidateValue("maxSplit", parts[3]) != nil {
				return "", options, false
			}
			f.maxSplit = &parts[3]
		}
		options.fragment = f
	}
	mux, hasMux := values["mux"]
	concurrency, hasConcurrency := values["muxConcurrency"]
	if hasMux || hasConcurrency {
		if !hasMux || !hasConcurrency || mux[0] != "true" || concurrency[0] == "" {
			return "", options, false
		}
		for _, r := range concurrency[0] {
			if r < '0' || r > '9' {
				return "", options, false
			}
		}
		n, err := strconv.Atoi(concurrency[0])
		if err != nil || n < 1 || n > 128 || capability.ValidateValue("tcpConcurrency", float64(n)) != nil {
			return "", options, false
		}
		options.muxConcurrency = &n
	}
	if options.fragment == nil && options.muxConcurrency == nil {
		return raw, options, true
	}
	kept := make([]string, 0)
	for _, part := range strings.Split(query, "&") {
		if part == "" {
			continue
		}
		key, _, _ := strings.Cut(part, "=")
		name, err := url.QueryUnescape(key)
		if err != nil {
			return "", functionalOptions{}, false
		}
		if name == "fragment" || name == "mux" || name == "muxConcurrency" {
			continue
		}
		kept = append(kept, part)
	}
	if len(kept) > 0 {
		base += "?" + strings.Join(kept, "&")
	}
	if hasFragment {
		base += "#" + fragment
	}
	return base, options, true
}

func convertSubscriptionRecord(raw string) (model.Profile, bool) {
	cleaned, options, ok := extractFunctionalOptions(stripProviderHints(raw))
	if !ok {
		return model.Profile{}, false
	}
	result, err := importer.Parse([]byte(cleaned), "uri")
	if err != nil || len(result.Profiles) != 1 {
		return model.Profile{}, false
	}
	profile := result.Profiles[0]
	if options.fragment != nil {
		f := options.fragment
		profile.Fragment = &model.FragmentBaseline{Length: f.length, Interval: f.interval, Packets: f.packets, MaxSplit: f.maxSplit}
	}
	if options.muxConcurrency != nil {
		profile.Outbound["mux"] = map[string]any{"enabled": true, "concurrency": float64(*options.muxConcurrency)}
	}
	if (options.fragment != nil || options.muxConcurrency != nil) && capability.Check(profile, nil) != nil {
		return model.Profile{}, false
	}
	return profile, true
}
