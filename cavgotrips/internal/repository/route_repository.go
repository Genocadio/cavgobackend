package repository

import (
	"cavgotrips/internal/models"
	"errors"
	"fmt"
	"strings"

	"gorm.io/gorm"
)

type routeRepository struct {
	db *gorm.DB
}

func NewRouteRepository(db *gorm.DB) RouteRepository {
	return &routeRepository{db: db}
}

// ValidateLocationExists checks if a location with the given ID exists
func (r *routeRepository) ValidateLocationExists(locationID int64) error {
	var count int64
	if err := r.db.Model(&models.Location{}).Where("id = ?", locationID).Count(&count).Error; err != nil {
		return err
	}
	if count == 0 {
		return models.NewValidationError(fmt.Sprintf("location with ID %d does not exist", locationID))
	}
	return nil
}

// ValidateAllLocationsExist validates that origin, destination, and all waypoint locations exist
func (r *routeRepository) ValidateAllLocationsExist(route *models.Route) error {
	// Validate origin location exists
	if err := r.ValidateLocationExists(route.OriginID); err != nil {
		return models.NewValidationError(fmt.Sprintf("origin location validation failed: %v", err))
	}

	// Validate destination location exists
	if err := r.ValidateLocationExists(route.DestinationID); err != nil {
		return models.NewValidationError(fmt.Sprintf("destination location validation failed: %v", err))
	}

	// Validate all waypoint locations exist
	for i, waypoint := range route.Waypoints {
		if err := r.ValidateLocationExists(waypoint.LocationID); err != nil {
			return models.NewValidationError(fmt.Sprintf("waypoint %d location validation failed: %v", i+1, err))
		}
	}

	return nil
}

func (r *routeRepository) CheckUniqueness(route *models.Route) error {
	var count int64

	// Check for routes with same origin, destination, and identical passthrough waypoint sequences
	if err := r.checkRouteWithSameWaypointSequence(route); err != nil {
		return err
	}

	// Check for route with same name (if provided)
	if route.Name != nil && *route.Name != "" {
		if err := r.db.Model(&models.Route{}).
			Where("name = ? AND id != ?", *route.Name, route.ID).
			Count(&count).Error; err != nil {
			return err
		}
		if count > 0 {
			return errors.New("a route with this name already exists")
		}
	}

	// Check for route with same Google Route ID (if provided)
	if route.GoogleRouteID != nil && *route.GoogleRouteID != "" {
		if err := r.db.Model(&models.Route{}).
			Where("google_route_id = ? AND id != ?", *route.GoogleRouteID, route.ID).
			Count(&count).Error; err != nil {
			return err
		}
		if count > 0 {
			return errors.New("a route with this Google Route ID already exists")
		}
	}

	return nil
}

// checkRouteWithSameWaypointSequence checks if a route with same origin, destination,
// and identical passthrough waypoint sequence already exists
func (r *routeRepository) checkRouteWithSameWaypointSequence(route *models.Route) error {
	// Get all routes with same origin and destination
	var existingRoutes []models.Route
	if err := r.db.Preload("Waypoints").
		Where("origin_id = ? AND destination_id = ? AND id != ?",
			route.OriginID, route.DestinationID, route.ID).
		Find(&existingRoutes).Error; err != nil {
		return err
	}

	// Get only passthrough waypoints from the new route (ordered by Order field)
	newPassthroughWaypoints := make([]int64, 0)
	for _, waypoint := range route.Waypoints {
		if waypoint.IsPassThrough {
			newPassthroughWaypoints = append(newPassthroughWaypoints, waypoint.LocationID)
		}
	}

	// Compare with each existing route
	for _, existingRoute := range existingRoutes {
		// Get passthrough waypoints from existing route (ordered by Order field)
		existingPassthroughWaypoints := make([]int64, 0)
		for _, waypoint := range existingRoute.Waypoints {
			if waypoint.IsPassThrough {
				existingPassthroughWaypoints = append(existingPassthroughWaypoints, waypoint.LocationID)
			}
		}

		// Compare the sequences
		if areWaypointSequencesEqual(newPassthroughWaypoints, existingPassthroughWaypoints) {
			if len(newPassthroughWaypoints) == 0 {
				return errors.New("a route with the same origin and destination (no passthrough waypoints) already exists")
			} else {
				return errors.New("a route with the same origin, destination, and passthrough waypoint sequence already exists")
			}
		}
	}

	return nil
}

// areWaypointSequencesEqual compares two waypoint sequences for equality
func areWaypointSequencesEqual(seq1, seq2 []int64) bool {
	if len(seq1) != len(seq2) {
		return false
	}

	for i, locationID := range seq1 {
		if locationID != seq2[i] {
			return false
		}
	}

	return true
}

func (r *routeRepository) Create(route *models.Route) error {
	// First validate all locations exist
	if err := r.ValidateAllLocationsExist(route); err != nil {
		return err
	}

	// Check uniqueness constraints
	if err := r.CheckUniqueness(route); err != nil {
		return err
	}

	return r.db.Transaction(func(tx *gorm.DB) error {
		// Store waypoints before clearing them
		waypoints := route.Waypoints

		// Clear waypoints to prevent GORM from creating them automatically
		route.Waypoints = nil

		// Create route without waypoints
		if err := tx.Create(route).Error; err != nil {
			return err
		}

		// Create waypoints if provided
		for i, waypoint := range waypoints {
			waypoint.ID = 0 // Reset ID to let database auto-generate
			waypoint.RouteID = route.ID
			waypoint.Order = i + 1 // Set order based on arrangement index
			if err := tx.Create(&waypoint).Error; err != nil {
				return err
			}
		}

		return nil
	})
}

func (r *routeRepository) GetAll() ([]models.Route, error) {
	var routes []models.Route
	err := r.db.Preload("Origin").Preload("Destination").Preload("Waypoints.Location").Find(&routes).Error
	return routes, err
}

func (r *routeRepository) GetAllPaginated(limit, offset int) ([]models.Route, int64, error) {
	var routes []models.Route
	var total int64

	// Get total count
	err := r.db.Model(&models.Route{}).Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	// Get paginated results
	err = r.db.Preload("Origin").Preload("Destination").Preload("Waypoints.Location").
		Limit(limit).Offset(offset).Find(&routes).Error
	return routes, total, err
}

func (r *routeRepository) GetByID(id int64) (*models.Route, error) {
	var route models.Route
	err := r.db.Preload("Origin").Preload("Destination").Preload("Waypoints.Location").First(&route, id).Error
	if err != nil {
		return nil, err
	}
	return &route, nil
}

func (r *routeRepository) GetByIDWithWaypoints(id int64) (*models.Route, error) {
	var route models.Route
	err := r.db.Preload("Waypoints.Location").First(&route, id).Error
	if err != nil {
		return nil, err
	}
	return &route, nil
}

func (r *routeRepository) Update(route *models.Route) error {
	// First validate all locations exist
	if err := r.ValidateAllLocationsExist(route); err != nil {
		return err
	}

	// Check uniqueness constraints (excluding the current route)
	if err := r.CheckUniqueness(route); err != nil {
		return err
	}

	return r.db.Transaction(func(tx *gorm.DB) error {
		// Store waypoints before clearing them
		waypoints := route.Waypoints

		// Clear waypoints to prevent GORM from creating them automatically
		route.Waypoints = nil

		// Update route without waypoints
		if err := tx.Save(route).Error; err != nil {
			return err
		}

		// Delete existing waypoints for this route
		if err := tx.Where("route_id = ?", route.ID).Delete(&models.RouteWaypoint{}).Error; err != nil {
			return err
		}

		// Create new waypoints if provided
		for i, waypoint := range waypoints {
			waypoint.ID = 0 // Reset ID to let database auto-generate
			waypoint.RouteID = route.ID
			waypoint.Order = i + 1 // Set order based on arrangement index
			if err := tx.Create(&waypoint).Error; err != nil {
				return err
			}
		}

		return nil
	})
}

func (r *routeRepository) Delete(id int64) error {
	return r.db.Transaction(func(tx *gorm.DB) error {
		// Delete waypoints first (due to foreign key constraint)
		if err := tx.Where("route_id = ?", id).Delete(&models.RouteWaypoint{}).Error; err != nil {
			return err
		}

		// Delete the route
		if err := tx.Delete(&models.Route{}, id).Error; err != nil {
			return err
		}

		return nil
	})
}

func (r *routeRepository) SearchByOriginDestination(origin, destination string) ([]models.Route, error) {
	var routes []models.Route
	query := r.db.Preload("Origin").Preload("Destination").Preload("Waypoints.Location")

	if origin != "" {
		query = query.Joins("JOIN locations origin ON routes.origin_id = origin.id").
			Where("LOWER(origin.custom_name) LIKE LOWER(?) OR LOWER(origin.google_place_name) LIKE LOWER(?)",
				"%"+origin+"%", "%"+origin+"%")
	}

	if destination != "" {
		query = query.Joins("JOIN locations destination ON routes.destination_id = destination.id").
			Where("LOWER(destination.custom_name) LIKE LOWER(?) OR LOWER(destination.google_place_name) LIKE LOWER(?)",
				"%"+destination+"%", "%"+destination+"%")
	}

	err := query.Find(&routes).Error
	return routes, err
}

func (r *routeRepository) SearchByOriginDestinationPaginated(origin, destination string, limit, offset int) ([]models.Route, int64, error) {
	var routes []models.Route
	var total int64

	// Build base query for counting
	countQuery := r.db.Model(&models.Route{})
	if origin != "" {
		countQuery = countQuery.Joins("JOIN locations origin ON routes.origin_id = origin.id").
			Where("LOWER(origin.custom_name) LIKE LOWER(?) OR LOWER(origin.google_place_name) LIKE LOWER(?)",
				"%"+origin+"%", "%"+origin+"%")
	}
	if destination != "" {
		countQuery = countQuery.Joins("JOIN locations destination ON routes.destination_id = destination.id").
			Where("LOWER(destination.custom_name) LIKE LOWER(?) OR LOWER(destination.google_place_name) LIKE LOWER(?)",
				"%"+destination+"%", "%"+destination+"%")
	}

	// Get total count
	err := countQuery.Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	// Build query for results
	query := r.db.Preload("Origin").Preload("Destination").Preload("Waypoints.Location")
	if origin != "" {
		query = query.Joins("JOIN locations origin ON routes.origin_id = origin.id").
			Where("LOWER(origin.custom_name) LIKE LOWER(?) OR LOWER(origin.google_place_name) LIKE LOWER(?)",
				"%"+origin+"%", "%"+origin+"%")
	}
	if destination != "" {
		query = query.Joins("JOIN locations destination ON routes.destination_id = destination.id").
			Where("LOWER(destination.custom_name) LIKE LOWER(?) OR LOWER(destination.google_place_name) LIKE LOWER(?)",
				"%"+destination+"%", "%"+destination+"%")
	}

	err = query.Limit(limit).Offset(offset).Find(&routes).Error
	return routes, total, err
}

func (r *routeRepository) FilterByCityRoute(cityRoute *bool) ([]models.Route, error) {
	var routes []models.Route
	query := r.db.Preload("Origin").Preload("Destination").Preload("Waypoints.Location")

	if cityRoute != nil {
		query = query.Where("city_route = ?", *cityRoute)
	}

	err := query.Find(&routes).Error
	return routes, err
}

func (r *routeRepository) FilterByCityRoutePaginated(cityRoute *bool, limit, offset int) ([]models.Route, int64, error) {
	var routes []models.Route
	var total int64

	// Build base query for counting
	countQuery := r.db.Model(&models.Route{})
	if cityRoute != nil {
		countQuery = countQuery.Where("city_route = ?", *cityRoute)
	}

	// Get total count
	err := countQuery.Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	// Build query for results
	query := r.db.Preload("Origin").Preload("Destination").Preload("Waypoints.Location")
	if cityRoute != nil {
		query = query.Where("city_route = ?", *cityRoute)
	}

	err = query.Limit(limit).Offset(offset).Find(&routes).Error
	return routes, total, err
}

func (r *routeRepository) FilterByProvinces(originProvince, destinationProvince string) ([]models.Route, error) {
	var routes []models.Route
	query := r.db.Preload("Origin").Preload("Destination").Preload("Waypoints.Location")

	if originProvince != "" {
		query = query.Joins("JOIN locations origin ON routes.origin_id = origin.id").
			Where("LOWER(origin.province) LIKE LOWER(?)", "%"+originProvince+"%")
	}

	if destinationProvince != "" {
		query = query.Joins("JOIN locations destination ON routes.destination_id = destination.id").
			Where("LOWER(destination.province) LIKE LOWER(?)", "%"+destinationProvince+"%")
	}

	err := query.Find(&routes).Error
	return routes, err
}

func (r *routeRepository) FilterByProvincesPaginated(originProvince, destinationProvince string, limit, offset int) ([]models.Route, int64, error) {
	var routes []models.Route
	var total int64

	// Build base query for counting
	countQuery := r.db.Model(&models.Route{})
	if originProvince != "" {
		countQuery = countQuery.Joins("JOIN locations origin ON routes.origin_id = origin.id").
			Where("LOWER(origin.province) LIKE LOWER(?)", "%"+originProvince+"%")
	}
	if destinationProvince != "" {
		countQuery = countQuery.Joins("JOIN locations destination ON routes.destination_id = destination.id").
			Where("LOWER(destination.province) LIKE LOWER(?)", "%"+destinationProvince+"%")
	}

	// Get total count
	err := countQuery.Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	// Build query for results
	query := r.db.Preload("Origin").Preload("Destination").Preload("Waypoints.Location")
	if originProvince != "" {
		query = query.Joins("JOIN locations origin ON routes.origin_id = origin.id").
			Where("LOWER(origin.province) LIKE LOWER(?)", "%"+originProvince+"%")
	}
	if destinationProvince != "" {
		query = query.Joins("JOIN locations destination ON routes.destination_id = destination.id").
			Where("LOWER(destination.province) LIKE LOWER(?)", "%"+destinationProvince+"%")
	}

	err = query.Limit(limit).Offset(offset).Find(&routes).Error
	return routes, total, err
}

func (r *routeRepository) SearchAndFilter(origin, destination string, cityRoute *bool, originProvince, destinationProvince string) ([]models.Route, error) {
	var routes []models.Route
	query := buildRouteSearchQuery(r.db, origin, destination, cityRoute, originProvince, destinationProvince)

	err := query.Find(&routes).Error
	return routes, err
}

func (r *routeRepository) SearchAndFilterPaginated(origin, destination string, cityRoute *bool, originProvince, destinationProvince string, limit, offset int) ([]models.Route, int64, error) {
	var routes []models.Route
	var total int64

	// Count against the same joined, filtered set
	countQuery := buildRouteSearchFilters(r.db.Model(&models.Route{}), origin, destination, cityRoute, originProvince, destinationProvince)
	if err := countQuery.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	query := buildRouteSearchQuery(r.db, origin, destination, cityRoute, originProvince, destinationProvince)
	err := query.Limit(limit).Offset(offset).Find(&routes).Error
	return routes, total, err
}

// buildRouteSearchQuery returns a query with the origin/destination locations
// always joined under distinct aliases (origin_loc/destination_loc) and the
// relevance-ordered search filters applied. Reusing a single join alias per
// side removes the double-join risk of the previous conditional joins.
func buildRouteSearchQuery(db *gorm.DB, origin, destination string, cityRoute *bool, originProvince, destinationProvince string) *gorm.DB {
	return buildRouteSearchFilters(
		db.Preload("Origin").Preload("Destination").Preload("Waypoints.Location"),
		origin, destination, cityRoute, originProvince, destinationProvince,
	).Order(routeRelevanceOrder(origin, destination))
}

// buildRouteSearchFilters applies the joins (always both sides, distinct
// aliases) and WHERE clauses. It does NOT add Preload or ORDER so callers can
// reuse it for COUNT queries.
func buildRouteSearchFilters(db *gorm.DB, origin, destination string, cityRoute *bool, originProvince, destinationProvince string) *gorm.DB {
	query := db.
		Joins("JOIN locations origin_loc ON routes.origin_id = origin_loc.id").
		Joins("JOIN locations destination_loc ON routes.destination_id = destination_loc.id")

	if origin != "" {
		query = query.Where("LOWER(origin_loc.custom_name) LIKE LOWER(?) OR LOWER(origin_loc.google_place_name) LIKE LOWER(?)",
			"%"+origin+"%", "%"+origin+"%")
	}

	if destination != "" {
		query = query.Where("LOWER(destination_loc.custom_name) LIKE LOWER(?) OR LOWER(destination_loc.google_place_name) LIKE LOWER(?)",
			"%"+destination+"%", "%"+destination+"%")
	}

	if cityRoute != nil {
		query = query.Where("routes.city_route = ?", *cityRoute)
	}

	if originProvince != "" {
		query = query.Where("LOWER(origin_loc.province) LIKE LOWER(?)", "%"+originProvince+"%")
	}

	if destinationProvince != "" {
		query = query.Where("LOWER(destination_loc.province) LIKE LOWER(?)", "%"+destinationProvince+"%")
	}

	return query
}

// routeRelevanceOrder ranks matching routes like the location search: custom
// name matches outrank Google place name matches, and word-start / prefix
// matches outrank contains matches. Origin matches are ranked below unbucketed
// destination matches so searching either side is deterministic.
func routeRelevanceOrder(origin, destination string) string {
	var clauses []string
	if origin != "" {
		clauses = append(clauses, routeSideRank("origin_loc", origin, 0))
	}
	if destination != "" {
		clauses = append(clauses, routeSideRank("destination_loc", destination, 20))
	}
	if len(clauses) == 0 {
		return "routes.id ASC"
	}
	return "CASE\n" + strings.Join(clauses, "\n") + "\nELSE 99\nEND, origin_loc.custom_name, destination_loc.custom_name"
}

// routeSideRank produces the WHEN clauses for one side of the route search.
// Rank offsets: custom name prefix=0, word-start=1, contains=2; google place
// name prefix=10, word-start=11, contains=12 (only when the custom name does
// not already contain the term).
func routeSideRank(alias, term string, offset int) string {
	lower := strings.ToLower(escapeLikeTerm(term))
	start := lower + "%"
	wordStart := "% " + lower + "%"
	contains := "%" + lower + "%"
	return fmt.Sprintf(`
		WHEN LOWER(%[1]s.custom_name) LIKE '%[2]s' THEN %[3]d
		WHEN LOWER(%[1]s.custom_name) LIKE '%[4]s' OR LOWER(%[1]s.custom_name) LIKE '%[2]s' THEN %[5]d
		WHEN LOWER(%[1]s.custom_name) LIKE '%[6]s' THEN %[7]d
		WHEN LOWER(%[1]s.google_place_name) LIKE '%[2]s' AND (%[1]s.custom_name IS NULL OR LOWER(%[1]s.custom_name) NOT LIKE '%[6]s') THEN %[8]d
		WHEN LOWER(%[1]s.google_place_name) LIKE '%[4]s' AND (%[1]s.custom_name IS NULL OR LOWER(%[1]s.custom_name) NOT LIKE '%[6]s') THEN %[9]d
		WHEN LOWER(%[1]s.google_place_name) LIKE '%[6]s' AND (%[1]s.custom_name IS NULL OR LOWER(%[1]s.custom_name) NOT LIKE '%[6]s') THEN %[10]d`,
		alias, start, offset, wordStart, offset+1, contains, offset+2, offset+10, offset+11, offset+12)
}

// escapeLikeTerm escapes LIKE wildcards so user terms match literally inside
// the ranking CASE (the WHERE clause remains fully parameterized).
func escapeLikeTerm(term string) string {
	return strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`, `'`, `''`).Replace(term)
}

func (r *routeRepository) GetRoutesByPriceRange(minPrice, maxPrice float64) ([]models.Route, error) {
	var routes []models.Route
	query := r.db.Preload("Origin").Preload("Destination").Preload("Waypoints.Location")

	if minPrice > 0 {
		query = query.Where("route_price >= ?", minPrice)
	}
	if maxPrice > 0 {
		query = query.Where("route_price <= ?", maxPrice)
	}

	err := query.Find(&routes).Error
	return routes, err
}

func (r *routeRepository) GetRoutesByPriceRangePaginated(minPrice, maxPrice float64, limit, offset int) ([]models.Route, int64, error) {
	var routes []models.Route
	var total int64

	// Build base query for counting
	countQuery := r.db.Model(&models.Route{})
	if minPrice > 0 {
		countQuery = countQuery.Where("route_price >= ?", minPrice)
	}
	if maxPrice > 0 {
		countQuery = countQuery.Where("route_price <= ?", maxPrice)
	}

	// Get total count
	err := countQuery.Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	// Build query for results
	query := r.db.Preload("Origin").Preload("Destination").Preload("Waypoints.Location")
	if minPrice > 0 {
		query = query.Where("route_price >= ?", minPrice)
	}
	if maxPrice > 0 {
		query = query.Where("route_price <= ?", maxPrice)
	}

	err = query.Limit(limit).Offset(offset).Find(&routes).Error
	return routes, total, err
}

func (r *routeRepository) GetRoutesByDistanceRange(minDistance, maxDistance int) ([]models.Route, error) {
	var routes []models.Route
	query := r.db.Preload("Origin").Preload("Destination").Preload("Waypoints.Location")

	if minDistance > 0 {
		query = query.Where("distance_meters >= ?", minDistance)
	}
	if maxDistance > 0 {
		query = query.Where("distance_meters <= ?", maxDistance)
	}

	err := query.Find(&routes).Error
	return routes, err
}

func (r *routeRepository) GetRoutesByDistanceRangePaginated(minDistance, maxDistance int, limit, offset int) ([]models.Route, int64, error) {
	var routes []models.Route
	var total int64

	// Build base query for counting
	countQuery := r.db.Model(&models.Route{})
	if minDistance > 0 {
		countQuery = countQuery.Where("distance_meters >= ?", minDistance)
	}
	if maxDistance > 0 {
		countQuery = countQuery.Where("distance_meters <= ?", maxDistance)
	}

	// Get total count
	err := countQuery.Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	// Build query for results
	query := r.db.Preload("Origin").Preload("Destination").Preload("Waypoints.Location")
	if minDistance > 0 {
		query = query.Where("distance_meters >= ?", minDistance)
	}
	if maxDistance > 0 {
		query = query.Where("distance_meters <= ?", maxDistance)
	}

	err = query.Limit(limit).Offset(offset).Find(&routes).Error
	return routes, total, err
}

func (r *routeRepository) GetRouteStatistics() (map[string]interface{}, error) {
	stats := make(map[string]interface{})

	// Total routes count
	var totalRoutes int64
	err := r.db.Model(&models.Route{}).Count(&totalRoutes).Error
	if err != nil {
		return nil, err
	}
	stats["total_routes"] = totalRoutes

	// City routes count
	var cityRoutes int64
	err = r.db.Model(&models.Route{}).Where("city_route = ?", true).Count(&cityRoutes).Error
	if err != nil {
		return nil, err
	}
	stats["city_routes"] = cityRoutes

	// Non-city routes count
	var nonCityRoutes int64
	err = r.db.Model(&models.Route{}).Where("city_route = ?", false).Count(&nonCityRoutes).Error
	if err != nil {
		return nil, err
	}
	stats["non_city_routes"] = nonCityRoutes

	// Average route price
	var avgPrice float64
	err = r.db.Model(&models.Route{}).Select("AVG(route_price)").Scan(&avgPrice).Error
	if err != nil {
		return nil, err
	}
	stats["average_price"] = avgPrice

	// Average distance
	var avgDistance float64
	err = r.db.Model(&models.Route{}).Select("AVG(distance_meters)").Scan(&avgDistance).Error
	if err != nil {
		return nil, err
	}
	stats["average_distance_meters"] = avgDistance

	// Price range
	var priceRange []float64
	err = r.db.Model(&models.Route{}).Select("MIN(route_price), MAX(route_price)").Scan(&priceRange).Error
	if err != nil {
		return nil, err
	}
	stats["price_range"] = map[string]float64{
		"min": priceRange[0],
		"max": priceRange[1],
	}

	// Distance range
	var distanceRange []int
	err = r.db.Model(&models.Route{}).Select("MIN(distance_meters), MAX(distance_meters)").Scan(&distanceRange).Error
	if err != nil {
		return nil, err
	}
	stats["distance_range"] = map[string]int{
		"min": distanceRange[0],
		"max": distanceRange[1],
	}

	return stats, nil
}
