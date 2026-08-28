package repository

import (
	"testing"

	"cavgotrips/internal/models"
)

func strPtr(s string) *string { return &s }

func testTrip(id int64, status string, company string) models.Trip {
	return models.Trip{
		ID:     id,
		Status: status,
		Vehicle: models.Vehicle{
			CompanyName: company,
		},
	}
}

func TestFilterTripsBySearch_OriginDestinationMatch(t *testing.T) {
	custom := strPtr("Kigali")
	trips := []models.Trip{
		{
			// origin + destination matched directly on the route
			ID:     1,
			Status: "SCHEDULED",
			Route: models.Route{
				Origin:      models.Location{CustomName: custom},
				Destination: models.Location{CustomName: strPtr("Musanze")},
			},
		},
		{
			// origin matched via waypoint (order 1), destination direct
			ID:     2,
			Status: "SCHEDULED",
			Route: models.Route{
				Origin:      models.Location{CustomName: strPtr("Other")},
				Destination: models.Location{CustomName: strPtr("Musanze")},
			},
			Waypoints: []models.TripWaypoint{
				{Order: 1, Location: models.Location{CustomName: custom}},
			},
		},
		{
			// destination waypoint appears BEFORE origin waypoint -> wrong order
			ID:     3,
			Status: "SCHEDULED",
			Route: models.Route{
				Origin:      models.Location{CustomName: strPtr("Other")},
				Destination: models.Location{CustomName: strPtr("Other2")},
			},
			Waypoints: []models.TripWaypoint{
				{Order: 0, Location: models.Location{CustomName: strPtr("Musanze")}},
				{Order: 1, Location: models.Location{CustomName: custom}},
			},
		},
	}

	got := FilterTripsBySearch(trips, "kigali", "musanze", "", false)
	if len(got) != 2 {
		t.Fatalf("expected 2 trips, got %d", len(got))
	}
	for _, tr := range got {
		if tr.ID == 3 {
			t.Errorf("trip 3 should be excluded (destination waypoint precedes origin)")
		}
	}
}

func TestFilterTripsBySearch_InProgressNoPendingWaypoints(t *testing.T) {
	trips := []models.Trip{
		{
			ID:     1,
			Status: "IN_PROGRESS",
			Route: models.Route{
				Origin:      models.Location{CustomName: strPtr("Kigali")},
				Destination: models.Location{CustomName: strPtr("Musanze")},
			},
			// no waypoints at all
			Waypoints: []models.TripWaypoint{},
		},
		{
			ID:     2,
			Status: "IN_PROGRESS",
			Route: models.Route{
				Origin:      models.Location{CustomName: strPtr("Kigali")},
				Destination: models.Location{CustomName: strPtr("Musanze")},
			},
			Waypoints: []models.TripWaypoint{
				{Order: 1, IsPassed: true, Location: models.Location{CustomName: strPtr("Huye")}},
			},
		},
		{
			ID:     3,
			Status: "IN_PROGRESS",
			Route: models.Route{
				Origin:      models.Location{CustomName: strPtr("Kigali")},
				Destination: models.Location{CustomName: strPtr("Musanze")},
			},
			Waypoints: []models.TripWaypoint{
				{Order: 1, IsPassed: false, Location: models.Location{CustomName: strPtr("Huye")}},
			},
		},
	}

	got := FilterTripsBySearch(trips, "kigali", "musanze", "", false)
	if len(got) != 1 {
		t.Fatalf("expected only trip 3 to be kept, got %d", len(got))
	}
	if got[0].ID != 3 {
		t.Errorf("expected trip 3, got %d", got[0].ID)
	}
}

func TestFilterTripsBySearch_SkipInProgressOnDirectMatch(t *testing.T) {
	trips := []models.Trip{
		{
			ID:     1,
			Status: "IN_PROGRESS",
			Route: models.Route{
				Origin:      models.Location{CustomName: strPtr("Kigali")},
				Destination: models.Location{CustomName: strPtr("Musanze")},
			},
		},
		{
			ID:     2,
			Status: "SCHEDULED",
			Route: models.Route{
				Origin:      models.Location{CustomName: strPtr("Kigali")},
				Destination: models.Location{CustomName: strPtr("Musanze")},
			},
		},
	}

	got := FilterTripsBySearch(trips, "kigali", "musanze", "", true)
	if len(got) != 1 || got[0].ID != 2 {
		t.Fatalf("expected only scheduled trip to remain with skipInProgressOnDirectMatch, got %d trips", len(got))
	}
}

func TestFilterTripsBySearch_Company(t *testing.T) {
	trips := []models.Trip{
		{
			ID:     1,
			Status: "SCHEDULED",
			Route: models.Route{
				Origin:      models.Location{CustomName: strPtr("Kigali")},
				Destination: models.Location{CustomName: strPtr("Musanze")},
			},
			Vehicle: models.Vehicle{CompanyName: "Volcano Express"},
		},
		{
			ID:     2,
			Status: "SCHEDULED",
			Route: models.Route{
				Origin:      models.Location{CustomName: strPtr("Kigali")},
				Destination: models.Location{CustomName: strPtr("Musanze")},
			},
			Vehicle: models.Vehicle{CompanyName: "Horizon"},
		},
	}

	got := FilterTripsBySearch(trips, "kigali", "musanze", "volcano", true)
	if len(got) != 1 || got[0].ID != 1 {
		t.Fatalf("expected trip 1 kept, got %d trips", len(got))
	}

	// Empty company term must not filter
	got = FilterTripsBySearch(trips, "kigali", "musanze", "", true)
	if len(got) != 2 {
		t.Fatalf("expected 2 trips when company is empty, got %d", len(got))
	}
}

func TestPaginateTrips(t *testing.T) {
	trips := []models.Trip{
		{ID: 1}, {ID: 2}, {ID: 3}, {ID: 4}, {ID: 5},
	}

	page, total := PaginateTrips(trips, 2, 0)
	if total != 5 || len(page) != 2 || page[0].ID != 1 || page[1].ID != 2 {
		t.Errorf("first page wrong: len=%d total=%d", len(page), total)
	}

	page, total = PaginateTrips(trips, 2, 4)
	if total != 5 || len(page) != 1 || page[0].ID != 5 {
		t.Errorf("last page wrong: len=%d total=%d", len(page), total)
	}

	page, total = PaginateTrips(trips, 2, 10)
	if total != 5 || len(page) != 0 {
		t.Errorf("out-of-range page should be empty: len=%d total=%d", len(page), total)
	}

	page, total = PaginateTrips([]models.Trip{}, 10, 0)
	if total != 0 || len(page) != 0 {
		t.Errorf("empty input wrong: len=%d total=%d", len(page), total)
	}

	// limit <= 0 returns everything after offset
	page, total = PaginateTrips(trips, 0, 1)
	if total != 5 || len(page) != 4 {
		t.Errorf("limit=0 should return remainder: len=%d total=%d", len(page), total)
	}
}

func TestSortTripsByMatchScore_PrefixBeforeContains(t *testing.T) {
	trips := []models.Trip{
		{
			ID:     1,
			Status: "SCHEDULED",
			Route: models.Route{
				Origin:      models.Location{CustomName: strPtr("Kigali City")},
				Destination: models.Location{CustomName: strPtr("Musanze")},
			},
		},
		{
			ID:     2,
			Status: "SCHEDULED",
			Route: models.Route{
				Origin:      models.Location{CustomName: strPtr("Nyanza")},
				Destination: models.Location{CustomName: strPtr("Kigali")},
			},
		},
		{
			ID:     3,
			Status: "SCHEDULED",
			Route: models.Route{
				Origin:      models.Location{CustomName: strPtr("Big Kigal")},
				Destination: models.Location{CustomName: strPtr("Musanze")},
			},
		},
	}

	got := SortTripsByMatchScore(trips, "kigali", "", "")
	if len(got) != 3 {
		t.Fatalf("expected 3 trips, got %d", len(got))
	}
	// Trip 1: field starts with term (score 3). Trip 2: contains term (score 1). Trip 3: no match (score 0).
	if got[0].ID != 1 || got[1].ID != 2 || got[2].ID != 3 {
		t.Errorf("order wrong: got %d, %d, %d", got[0].ID, got[1].ID, got[2].ID)
	}
}
