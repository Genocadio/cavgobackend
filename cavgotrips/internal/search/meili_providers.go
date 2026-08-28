package search

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"cavgotrips/internal/models"
	"cavgotrips/internal/repository"
)

// indexConfigs returns the full Meilisearch index configuration for the three
// searchable entities. Documents embed the full domain struct (so round-trips
// restore every field) plus denormalized searchable/filterable fields.
func indexConfigs() []indexSettings {
	typo := true
	return []indexSettings{
		{
			UID:        entityLocations,
			PrimaryKey: "id",
			Searchable: []string{"custom_name", "google_place_name", "code", "province", "district"},
			Filterable: []string{"province", "district"},
			RankingRules: []string{
				"words",
				"typo",
				"proximity",
				"attribute",
				"sort",
				"exact",
			},
			TypoTolerance: &typo,
		},
		{
			UID:        entityRoutes,
			PrimaryKey: "id",
			Searchable: []string{
				"name",
				"origin_custom_name",
				"origin_google_place_name",
				"destination_custom_name",
				"destination_google_place_name",
				"origin_province",
				"destination_province",
			},
			Filterable: []string{"city_route", "origin_province", "destination_province"},
			RankingRules: []string{
				"words",
				"typo",
				"proximity",
				"attribute",
				"sort",
				"exact",
			},
			TypoTolerance: &typo,
		},
		{
			UID:        entityTrips,
			PrimaryKey: "id",
			Searchable: []string{
				"company_name",
				"origin_custom_name",
				"origin_google_place_name",
				"origin_code",
				"destination_custom_name",
				"destination_google_place_name",
				"destination_code",
			},
			Filterable: []string{"status", "route_city_route"},
			RankingRules: []string{
				"words",
				"typo",
				"proximity",
				"attribute",
				"sort",
				"exact",
			},
			TypoTolerance: &typo,
		},
	}
}

// locationSearchDoc embeds the full Location (fields are already flat, so the
// searchable attributes map directly onto the model JSON keys).
type locationSearchDoc struct {
	models.Location
}

/********************* Locations *********************/

type meiliLocationProvider struct {
	client *MeiliClient
}

func (p *meiliLocationProvider) SearchLocationsPaginated(ctx context.Context, searchTerm string, page, limit int) ([]models.Location, int64, error) {
	resp, err := p.client.Search(ctx, entityLocations, meiliSearchRequest{
		Q:      searchTerm,
		Offset: (page - 1) * limit,
		Limit:  limit,
	})
	if err != nil {
		return nil, 0, err
	}
	locations := make([]models.Location, 0, len(resp.Hits))
	for _, hit := range resp.Hits {
		var doc locationSearchDoc
		if err := json.Unmarshal(hit, &doc); err != nil {
			return nil, 0, err
		}
		locations = append(locations, doc.Location)
	}
	return locations, resp.EstimatedTotalHits, nil
}

/********************* Routes *********************/

// routeSearchDoc embeds the full Route plus denormalized origin/destination
// search fields.
type routeSearchDoc struct {
	models.Route
	OriginCustomName           *string `json:"origin_custom_name"`
	OriginGooglePlaceName      *string `json:"origin_google_place_name"`
	OriginProvince             *string `json:"origin_province"`
	DestinationCustomName      *string `json:"destination_custom_name"`
	DestinationGooglePlaceName *string `json:"destination_google_place_name"`
	DestinationProvince        *string `json:"destination_province"`
}

func newRouteSearchDoc(route models.Route) routeSearchDoc {
	doc := routeSearchDoc{Route: route}
	if route.Origin.ID != 0 {
		doc.OriginCustomName = route.Origin.CustomName
		doc.OriginGooglePlaceName = route.Origin.GooglePlaceName
		doc.OriginProvince = route.Origin.Province
	}
	if route.Destination.ID != 0 {
		doc.DestinationCustomName = route.Destination.CustomName
		doc.DestinationGooglePlaceName = route.Destination.GooglePlaceName
		doc.DestinationProvince = route.Destination.Province
	}
	return doc
}

type meiliRouteProvider struct {
	client *MeiliClient
}

func (p *meiliRouteProvider) SearchRoutesPaginated(ctx context.Context, filters RouteFilters, page, limit int) ([]models.Route, int64, error) {
	query, attrs, filter := buildRouteMeiliRequest(filters)
	hits, _, err := p.client.SearchAll(ctx, entityRoutes, query, attrs, filter, maxPostFilterFetch)
	if err != nil {
		return nil, 0, err
	}
	routes := make([]models.Route, 0, len(hits))
	for _, hit := range hits {
		var doc routeSearchDoc
		if err := json.Unmarshal(hit, &doc); err != nil {
			return nil, 0, err
		}
		routes = append(routes, doc.Route)
	}
	routes = filterRoutes(routes, filters)
	paged, total := paginate(routes, page, limit)
	return paged, total, nil
}

func buildRouteMeiliRequest(f RouteFilters) (query string, attrs []string, filter *string) {
	switch {
	case f.Origin != "":
		query = f.Origin
		attrs = []string{"origin_custom_name", "origin_google_place_name", "name"}
	case f.Destination != "":
		query = f.Destination
		attrs = []string{"destination_custom_name", "destination_google_place_name", "name"}
	}
	if f.CityRoute != nil {
		c := fmt.Sprintf("city_route = %t", *f.CityRoute)
		filter = &c
	}
	return
}

// filterRoutes applies the secondary filters (the side not used as the primary
// search term, province filters and city_route) in memory, matching the SQL
// search semantics.
func filterRoutes(routes []models.Route, f RouteFilters) []models.Route {
	out := routes[:0]
	for _, route := range routes {
		if f.CityRoute != nil && route.CityRoute != *f.CityRoute {
			continue
		}
		if !locNameMatches(route.Origin, f.Origin) || !locNameMatches(route.Destination, f.Destination) {
			continue
		}
		if f.OriginProvince != "" && !containsFold(strVal(route.Origin.Province), f.OriginProvince) {
			continue
		}
		if f.DestinationProvince != "" && !containsFold(strVal(route.Destination.Province), f.DestinationProvince) {
			continue
		}
		out = append(out, route)
	}
	return out
}

func locNameMatches(loc models.Location, term string) bool {
	if term == "" {
		return true
	}
	return (loc.CustomName != nil && containsFold(*loc.CustomName, term)) ||
		(loc.GooglePlaceName != nil && containsFold(*loc.GooglePlaceName, term))
}

/********************* Trips *********************/

// tripSearchDoc embeds the full Trip plus denormalized origin/destination and
// company fields for searchability and filtering.
type tripSearchDoc struct {
	models.Trip
	CompanyName                *string `json:"company_name"`
	OriginCustomName           *string `json:"origin_custom_name"`
	OriginGooglePlaceName      *string `json:"origin_google_place_name"`
	OriginCode                 *string `json:"origin_code"`
	DestinationCustomName      *string `json:"destination_custom_name"`
	DestinationGooglePlaceName *string `json:"destination_google_place_name"`
	DestinationCode            *string `json:"destination_code"`
	RouteCityRoute             bool    `json:"route_city_route"`
}

func newTripSearchDoc(trip models.Trip) tripSearchDoc {
	doc := tripSearchDoc{Trip: trip}
	if trip.Vehicle.CompanyName != "" {
		doc.CompanyName = &trip.Vehicle.CompanyName
	}
	if trip.Route.Origin.ID != 0 {
		doc.OriginCustomName = trip.Route.Origin.CustomName
		doc.OriginGooglePlaceName = trip.Route.Origin.GooglePlaceName
		doc.OriginCode = trip.Route.Origin.Code
	}
	if trip.Route.Destination.ID != 0 {
		doc.DestinationCustomName = trip.Route.Destination.CustomName
		doc.DestinationGooglePlaceName = trip.Route.Destination.GooglePlaceName
		doc.DestinationCode = trip.Route.Destination.Code
	}
	doc.RouteCityRoute = trip.Route.CityRoute
	return doc
}

type meiliTripProvider struct {
	client *MeiliClient
}

func (p *meiliTripProvider) SearchTripsPaginated(ctx context.Context, filters TripFilters, page, limit int) ([]models.Trip, int64, error) {
	query, attrs, filter := buildTripMeiliRequest(filters)
	hits, _, err := p.client.SearchAll(ctx, entityTrips, query, attrs, filter, maxPostFilterFetch)
	if err != nil {
		return nil, 0, err
	}
	trips := make([]models.Trip, 0, len(hits))
	for _, hit := range hits {
		var doc tripSearchDoc
		if err := json.Unmarshal(hit, &doc); err != nil {
			return nil, 0, err
		}
		trips = append(trips, doc.Trip)
	}
	skipInProgress := filters.CityRoute != nil && *filters.CityRoute
	filtered := repository.FilterTripsBySearch(trips, filters.Origin, filters.Destination, filters.Company, skipInProgress)
	sorted := repository.SortTripsByMatchScore(filtered, filters.Origin, filters.Destination, filters.Company)
	paged, total := paginate(sorted, page, limit)
	return paged, total, nil
}

func buildTripMeiliRequest(f TripFilters) (query string, attrs []string, filter *string) {
	switch {
	case f.Origin != "":
		query = f.Origin
		attrs = []string{"origin_custom_name", "origin_google_place_name", "origin_code"}
	case f.Destination != "":
		query = f.Destination
		attrs = []string{"destination_custom_name", "destination_google_place_name", "destination_code"}
	case f.Company != "":
		query = f.Company
		attrs = []string{"company_name"}
	}
	conds := []string{`status NOT IN ["CANCELLED","COMPLETED"]`}
	if f.CityRoute != nil {
		conds = append(conds, fmt.Sprintf("route_city_route = %t", *f.CityRoute))
	}
	if len(conds) > 0 {
		c := strings.Join(conds, " AND ")
		filter = &c
	}
	return
}

/********************* shared helpers *********************/

func paginate[T any](items []T, page, limit int) ([]T, int64) {
	total := int64(len(items))
	start := (page - 1) * limit
	if limit <= 0 {
		start = 0
	}
	if start >= len(items) {
		return []T{}, total
	}
	end := start + limit
	if limit <= 0 || end > len(items) {
		end = len(items)
	}
	return items[start:end], total
}

func containsFold(s, sub string) bool {
	return strings.Contains(strings.ToLower(s), strings.ToLower(sub))
}

func strVal(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}
