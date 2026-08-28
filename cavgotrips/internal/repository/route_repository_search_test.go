package repository

import (
	"regexp"
	"strings"
	"testing"
)

func TestEscapeLikeTerm(t *testing.T) {
	cases := map[string]string{
		"simple":        "simple",
		"100% musanze":  `100\% musanze`,
		"50_off":        `50\_off`,
		"it's":          `it''s`,
		`back\slash`:    `back\\slash`,
		"%wild%'_all\\": `\%wild\%''\_all\\`,
	}
	for in, want := range cases {
		if got := escapeLikeTerm(in); got != want {
			t.Errorf("escapeLikeTerm(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestRouteRelevanceOrder_EmptyFallsBackToID(t *testing.T) {
	got := routeRelevanceOrder("", "")
	if got != "routes.id ASC" {
		t.Errorf("expected default order, got %q", got)
	}
}

func TestRouteRelevanceOrder_UsesDistinctAliases(t *testing.T) {
	got := routeRelevanceOrder("kigali", "musanze")
	if !strings.Contains(got, "origin_loc") || !strings.Contains(got, "destination_loc") {
		t.Errorf("order must reference joined aliases: %q", got)
	}
	if strings.Contains(got, "?") {
		t.Errorf("ranking CASE must not use parameter placeholders: %q", got)
	}
	if !strings.HasPrefix(got, "CASE") {
		t.Errorf("expected a CASE expression, got %q", got)
	}
}

func TestRouteSideRank_Offsets(t *testing.T) {
	got := routeSideRank("origin_loc", "kigali", 0)

	// Custom-name ranks occupy 0/1/2; google ranks 10/11/12 (origin offset 0).
	for _, want := range []string{"THEN 0", "THEN 1", "THEN 2", "THEN 10", "THEN 11", "THEN 12"} {
		if !strings.Contains(got, want) {
			t.Errorf("routeSideRank missing rank %q:\n%s", want, got)
		}
	}
	if strings.Contains(got, "THEN 20") || strings.Contains(got, "THEN 30") {
		t.Errorf("origin offset leaked into ranks:\n%s", got)
	}
}

func TestRouteSideRank_DestinationOffset(t *testing.T) {
	got := routeSideRank("destination_loc", "huye", 20)
	// Destination offsets: custom 20/21/22, google 30/31/32.
	for _, want := range []string{"THEN 20", "THEN 21", "THEN 22", "THEN 30", "THEN 31", "THEN 32"} {
		if !strings.Contains(got, want) {
			t.Errorf("routeSideRank missing rank %q:\n%s", want, got)
		}
	}
}

func TestRouteRelevanceOrder_SQLWellFormed(t *testing.T) {
	got := routeRelevanceOrder("kigali", "")
	// The generated CASE should be valid to at least the extent of balanced
	// WHEN/THEN/ELSE/END tokens.
	re := regexp.MustCompile(`\b(WHEN|THEN)\b`)
	matches := re.FindAllString(got, -1)
	if len(matches) == 0 || len(matches)%2 != 0 {
		t.Fatalf("unbalanced WHEN/THEN in ranking SQL:\n%s", got)
	}
	if !strings.Contains(got, "ELSE 99") {
		t.Errorf("expected ELSE 99 fallback:\n%s", got)
	}
	if !strings.Contains(got, "\nEND") {
		t.Errorf("expected closing END:\n%s", got)
	}
}
