package policy

import "testing"

func TestConnectivityRequiresAllTargetsBothRepeatsAndVerifiedTLS(t *testing.T) {
	p := Default()
	expected := []string{"https://www.google.com/", "https://www.youtube.com/", "https://github.com/"}
	for i, probe := range p.Probes {
		if probe.URL != expected[i] || probe.Kind != "connectivity" {
			t.Fatal(probe)
		}
	}
	for _, code := range []int{200, 302, 403, 429, 500, 599} {
		a := builtinAttempt(p)
		for i := range a.Results {
			a.Results[i].HTTPStatus = code
			a.Results[i].ContentVerified = false
		}
		if got := Evaluate(p, a); got != "pass" {
			t.Fatal(code, got)
		}
	}
	for index := range builtinAttempt(p).Results {
		a := builtinAttempt(p)
		a.Results = append(a.Results[:index], a.Results[index+1:]...)
		if Evaluate(p, a) == "pass" {
			t.Fatal("missing target/repeat accepted", index)
		}
		a = builtinAttempt(p)
		a.Results[index].TLSVerified = false
		if Evaluate(p, a) == "pass" {
			t.Fatal("unverified TLS accepted", index)
		}
	}
	for _, code := range []int{0, 199, 600} {
		a := builtinAttempt(p)
		a.Results[0].HTTPStatus = code
		if Evaluate(p, a) == "pass" {
			t.Fatal("invalid status accepted", code)
		}
	}
}
