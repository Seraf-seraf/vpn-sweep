package space

import (
	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"net"
)

func (g *Generator) ForProfile(profile model.Profile) (*Generator, error) {
	if g.Spec.Version == 1 {
		return g, nil
	}
	hasMux := false
	for _, d := range g.Spec.Parameters {
		if d.Name == "mux" {
			hasMux = true
		}
	}
	params := func() map[string]any {
		p := map[string]any{}
		if hasMux {
			p["mux"] = false
		}
		return p
	}
	fragment := params()
	fragment["fragment"] = true
	fragment["packets"] = "1-1"
	fragment["length"] = "1"
	fragment["interval"] = "0"
	canFragment := capability.Check(profile, fragment) == nil
	fragment["packets"] = "tlshello"
	canTLSHello := capability.Check(profile, fragment) == nil
	mux := params()
	mux["mux"] = true
	canMux := capability.Check(profile, mux) == nil
	fingerprint := params()
	fingerprint["fingerprint"] = "chrome"
	canFingerprint := capability.Check(profile, fingerprint) == nil
	noise := params()
	noise["noise"] = true
	noise["noises"] = noiseValue(noisePreset, 0)
	canNoise := capability.Check(profile, noise) == nil

	spec := g.Spec
	spec.Parameters = make([]Domain, 0, len(g.Spec.Parameters))
	for _, d := range g.Spec.Parameters {
		switch d.Name {
		case "fingerprint":
			if !canFingerprint {
				continue
			}
		case "fragment", "mux", "noise":
			allowed := canFragment
			if d.Name == "mux" {
				allowed = canMux
			} else if d.Name == "noise" {
				allowed = canNoise
			}
			if !allowed {
				values := make([]any, 0, len(d.Values))
				for _, value := range d.Values {
					if value == false {
						values = append(values, value)
					}
				}
				if len(values) == 0 {
					return nil, model.Err("profile_space_empty", "space")
				}
				d.Values = values
			}
		case "packets":
			if !canTLSHello {
				values := make([]any, 0, len(d.Values))
				for _, value := range d.Values {
					if value != "tlshello" {
						values = append(values, value)
					}
				}
				if len(values) == 0 {
					return nil, model.Err("profile_space_empty", "space")
				}
				d.Values = values
			}
		case "noises":
			if d.Preset == noisePreset {
				d.Preset = profileNoisePreset(profile)
			}
		}
		spec.Parameters = append(spec.Parameters, d)
	}
	return New(spec)
}

func profileNoisePreset(profile model.Profile) string {
	settings, _ := profile.Outbound["settings"].(map[string]any)
	servers, _ := settings["servers"].([]any)
	if len(servers) != 1 {
		return noisePresetIP
	}
	server, _ := servers[0].(map[string]any)
	address, _ := server["address"].(string)
	ip := net.ParseIP(address)
	if ip == nil {
		return noisePresetIP
	}
	if ip.To4() != nil {
		return noisePresetIPv4
	}
	return noisePresetIPv6
}
