package repository

import (
	"cavgotrips/internal/models"
	"sort"
	"strconv"
	"strings"
	"time"

	"gorm.io/gorm"
)

// driverIDCondition creates a WHERE condition that handles both integer and string driver IDs in JSON
// Excludes trips with driver ID 0 (no driver assigned)
func driverIDCondition(db *gorm.DB, driverID int64) *gorm.DB {
	// Try integer cast first, if that fails, try string comparison
	// Also exclude trips with driver ID 0 (no driver assigned)
	return db.Where("((vehicle->'driver'->>'id')::int = ? OR vehicle->'driver'->>'id' = ?) AND (vehicle->'driver'->>'id')::int > 0", driverID, strconv.FormatInt(driverID, 10))
}

type tripRepository struct {
	db *gorm.DB
}

func NewTripRepository(db *gorm.DB) TripRepository {
	return &tripRepository{db: db}
}

func (r *tripRepository) Create(trip *models.Trip) error {
	return r.db.Create(trip).Error
}

// BackfillRemainingSeats sets remaining_seats to seats for trips where it is NULL
func (r *tripRepository) BackfillRemainingSeats() error {
	return r.db.Model(&models.Trip{}).
		Where("remaining_seats IS NULL").
		Update("remaining_seats", gorm.Expr("seats")).Error
}

func (r *tripRepository) CreateWaypoint(waypoint *models.TripWaypoint) error {
	return r.db.Create(waypoint).Error
}

func (r *tripRepository) GetAll() ([]models.Trip, error) {
	var trips []models.Trip
	err := r.db.Preload("Route.Origin").
		Preload("Route.Destination").
		Preload("Waypoints.Location").
		Order("created_at DESC").
		Find(&trips).Error
	return trips, err
}

func (r *tripRepository) GetByID(id int64) (*models.Trip, error) {
	var trip models.Trip
	err := r.db.First(&trip, id).Error
	if err != nil {
		return nil, err
	}
	return &trip, nil
}

func (r *tripRepository) GetByIDWithRelations(id int64) (*models.Trip, error) {
	var trip models.Trip
	err := r.db.Preload("Route.Origin").
		Preload("Route.Destination").
		Preload("Waypoints.Location").
		First(&trip, id).Error
	if err != nil {
		return nil, err
	}
	return &trip, nil
}

func (r *tripRepository) UpdateProgress(id int64, updates map[string]interface{}) error {
	return r.db.Model(&models.Trip{}).Where("id = ?", id).Updates(updates).Error
}

func (r *tripRepository) UpdateWaypointProgress(waypointID int64, updates map[string]interface{}) error {
	return r.db.Model(&models.TripWaypoint{}).Where("id = ?", waypointID).Updates(updates).Error
}

func (r *tripRepository) MarkWaypointPassed(waypointID int64, timestamp int64) error {
	return r.db.Model(&models.TripWaypoint{}).Where("id = ?", waypointID).Updates(map[string]interface{}{
		"is_passed":        true,
		"passed_timestamp": timestamp,
	}).Error
}

func (r *tripRepository) GetTripsByStatus(status string) ([]models.Trip, error) {
	var trips []models.Trip
	err := r.db.Where("status = ?", status).
		Preload("Route.Origin").
		Preload("Route.Destination").
		Preload("Waypoints.Location").
		Order("created_at DESC").
		Find(&trips).Error
	return trips, err
}

func (r *tripRepository) GetTripsByCarPlate(carPlate string) ([]models.Trip, error) {
	var trips []models.Trip
	err := r.db.Where("car_plate = ?", carPlate).
		Preload("Route.Origin").
		Preload("Route.Destination").
		Preload("Waypoints.Location").
		Order("created_at DESC").
		Find(&trips).Error
	return trips, err
}

func (r *tripRepository) GetTripsByFilters(origin, destination, company string) ([]models.Trip, error) {
	var trips []models.Trip
	db := r.db.Preload("Route.Origin").Preload("Route.Destination").Preload("Waypoints.Location").
		Where("status NOT IN ?", []string{"CANCELLED", "COMPLETED"}).
		Order("created_at DESC")

	if company != "" {
		db = db.Where("LOWER(trips.vehicle->>'company_name') LIKE ?", "%"+strings.ToLower(company)+"%")
	}

	err := db.Find(&trips).Error
	if err != nil {
		return nil, err
	}

	filteredTrips := FilterTripsBySearch(trips, origin, destination, company, false)

	return SortTripsByMatchScore(filteredTrips, origin, destination, company), nil
}

func (r *tripRepository) GetTripsByFiltersPaginated(origin, destination, company string, limit, offset int) ([]models.Trip, int64, error) {
	// Filter (and sort) before paginating so that `total` and the returned page
	// slice describe the same filtered result set. Applying SQL limit/offset
	// before the in-memory filter would make the total unreachable through
	// paging.
	trips, err := r.GetTripsByFilters(origin, destination, company)
	if err != nil {
		return nil, 0, err
	}

	page, total := PaginateTrips(trips, limit, offset)
	return page, total, nil
}

func (r *tripRepository) GetTripsByVehicleID(vehicleID int64) ([]models.Trip, error) {
	var trips []models.Trip
	err := r.db.Preload("Route.Origin").
		Preload("Route.Destination").
		Preload("Waypoints.Location").
		Where("vehicle_id = ?", vehicleID).
		Order("created_at DESC").
		Find(&trips).Error
	if err != nil {
		return nil, err
	}
	return trips, nil
}

func (r *tripRepository) GetTripsByVehicleIDPaginated(vehicleID int64, statuses []string, limit, offset int) ([]models.Trip, int64, error) {
	var trips []models.Trip
	db := r.db.Preload("Route.Origin").
		Preload("Route.Destination").
		Preload("Waypoints.Location").
		Where("vehicle_id = ?", vehicleID).
		Order("created_at DESC")

	if len(statuses) > 0 {
		db = db.Where("status IN ?", statuses)
	}

	var total int64
	err := db.Session(&gorm.Session{}).Model(&models.Trip{}).Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	if limit > 0 {
		db = db.Limit(limit)
	}
	if offset > 0 {
		db = db.Offset(offset)
	}

	err = db.Find(&trips).Error
	if err != nil {
		return nil, 0, err
	}

	return trips, total, nil
}

func (r *tripRepository) GetLatestTripByVehicleID(vehicleID int64) (*models.Trip, error) {
	var trip models.Trip
	err := r.db.Preload("Route.Origin").
		Preload("Route.Destination").
		Preload("Waypoints.Location").
		Where("vehicle_id = ?", vehicleID).
		Order("created_at DESC").
		First(&trip).Error
	if err != nil {
		return nil, err
	}
	return &trip, nil
}

func (r *tripRepository) GetTripsByDriverID(driverID int64) ([]models.Trip, error) {
	var trips []models.Trip
	err := driverIDCondition(r.db.Preload("Route.Origin").
		Preload("Route.Destination").
		Preload("Waypoints.Location"), driverID).
		Order("created_at DESC").
		Find(&trips).Error
	return trips, err
}

func (r *tripRepository) GetTripsByCompanyID(companyID int64, driverID *int64, vehicleID *int64, fromDate *time.Time, afterTripID *int64, limit, offset int) ([]models.Trip, int64, error) {
	var trips []models.Trip
	db := r.db.Preload("Route.Origin").
		Preload("Route.Destination").
		Preload("Waypoints.Location").
		Where("(trips.vehicle->>'company_id')::int = ?", companyID).
		Where("DATE_TRUNC('month', trips.created_at) = DATE_TRUNC('month', CURRENT_DATE)").
		Order("trips.updated_at DESC, trips.created_at DESC")

	// Apply optional driver filter
	if driverID != nil && *driverID > 0 {
		db = driverIDCondition(db, *driverID)
	}

	// Apply optional vehicle filter
	if vehicleID != nil && *vehicleID > 0 {
		db = db.Where("trips.vehicle_id = ?", *vehicleID)
	}

	// Apply optional from_date filter
	if fromDate != nil {
		db = db.Where("trips.created_at >= ?", *fromDate)
	}

	// Apply optional after_trip_id filter - return trips updated after the specified trip
	if afterTripID != nil && *afterTripID > 0 {
		var afterTrip models.Trip
		err := r.db.First(&afterTrip, *afterTripID).Error
		if err == nil {
			// Only apply filter if the trip exists and belongs to the same company
			var afterTripCompanyID int64
			err = r.db.Model(&models.Trip{}).
				Select("(vehicle->>'company_id')::int").
				Where("id = ?", *afterTripID).
				Scan(&afterTripCompanyID).Error
			if err == nil && afterTripCompanyID == companyID {
				// Get the updated_at timestamp of the reference trip
				var afterTripUpdatedAt time.Time
				err = r.db.Model(&models.Trip{}).
					Select("updated_at").
					Where("id = ?", *afterTripID).
					Scan(&afterTripUpdatedAt).Error
				if err == nil {
					// Return trips updated after this timestamp
					db = db.Where("trips.updated_at > ?", afterTripUpdatedAt)
				}
			}
		}
	}

	// Get total count before pagination (clone the query to avoid affecting the main query)
	var total int64
	countDB := db.Session(&gorm.Session{})
	err := countDB.Model(&models.Trip{}).Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	// Apply pagination
	if limit > 0 {
		db = db.Limit(limit)
	}
	if offset > 0 {
		db = db.Offset(offset)
	}

	if err = db.Find(&trips).Error; err != nil {
		return nil, 0, err
	}
	return trips, total, nil
}

// GetAllTripsInternal gets all trips from last 30 days with optional last_update_time filter
func (r *tripRepository) GetAllTripsInternal(lastUpdateTime *time.Time, limit, offset int) ([]models.Trip, int64, error) {
	var trips []models.Trip

	// Calculate 30 days ago
	thirtyDaysAgo := time.Now().AddDate(0, 0, -30)

	db := r.db.Preload("Route.Origin").
		Preload("Route.Destination").
		Preload("Waypoints.Location").
		Where("trips.created_at >= ?", thirtyDaysAgo).
		Order("trips.updated_at DESC, trips.created_at DESC")

	// Apply optional last_update_time filter
	// Return trips where created_at >= last_update_time OR updated_at >= last_update_time
	if lastUpdateTime != nil {
		db = db.Where("(trips.created_at >= ? OR trips.updated_at >= ?)", *lastUpdateTime, *lastUpdateTime)
	}

	// Get total count before pagination
	var total int64
	countDB := db.Session(&gorm.Session{})
	err := countDB.Model(&models.Trip{}).Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	// Apply pagination
	if limit > 0 {
		db = db.Limit(limit)
	}
	if offset > 0 {
		db = db.Offset(offset)
	}

	if err = db.Find(&trips).Error; err != nil {
		return nil, 0, err
	}

	return trips, total, nil
}

func (r *tripRepository) GetTripsByCityRoute(cityRoute bool) ([]models.Trip, error) {
	var trips []models.Trip
	err := r.db.Preload("Route.Origin").
		Preload("Route.Destination").
		Preload("Waypoints.Location").
		Joins("JOIN routes ON trips.route_id = routes.id").
		Where("routes.city_route = ?", cityRoute).
		Order("trips.created_at DESC").
		Find(&trips).Error
	if err != nil {
		return nil, err
	}
	return trips, nil
}

// GetTripsByFiltersWithCityRoute filters by origin, destination, company, and cityRoute (routes.city_route)
func (r *tripRepository) GetTripsByFiltersWithCityRoute(origin, destination, company string, cityRoute bool, limit, offset int) ([]models.Trip, int64, error) {
	var trips []models.Trip
	db := r.db.Preload("Route.Origin").Preload("Route.Destination").Preload("Waypoints.Location").
		Joins("JOIN routes ON trips.route_id = routes.id").
		Where("routes.city_route = ?", cityRoute).
		Where("trips.status NOT IN ?", []string{"CANCELLED", "COMPLETED"}).
		Order("trips.created_at DESC")

	if company != "" {
		db = db.Where("LOWER(trips.vehicle->>'company_name') LIKE ?", "%"+strings.ToLower(company)+"%")
	}

	err := db.Find(&trips).Error
	if err != nil {
		return nil, 0, err
	}

	// Filter before paginating so total reflects the filtered result set.
	// In-progress trips that directly match origin+destination are skipped.
	filteredTrips := FilterTripsBySearch(trips, origin, destination, company, true)
	sortedTrips := SortTripsByMatchScore(filteredTrips, origin, destination, company)

	page, total := PaginateTrips(sortedTrips, limit, offset)
	return page, total, nil
}

// containsIgnoreCase checks if substr is in s, case-insensitive
func containsIgnoreCase(s, substr string) bool {
	return strings.Contains(strings.ToLower(s), strings.ToLower(substr))
}

// isNumericString checks if a string contains only numeric characters
func isNumericString(s string) bool {
	for _, char := range s {
		if char < '0' || char > '9' {
			return false
		}
	}
	return len(s) > 0
}

// matchesLocationCode checks if a location code matches the search term
func matchesLocationCode(locationCode *string, searchTerm string) bool {
	if locationCode == nil {
		return false
	}
	return strings.HasPrefix(*locationCode, searchTerm)
}

// checkLocationMatch checks if a location matches the search term (by name or code)
func checkLocationMatch(location *models.Location, searchTerm string) bool {
	if location == nil {
		return false
	}

	// If search term is numeric, check by location code
	if isNumericString(searchTerm) {
		return matchesLocationCode(location.Code, searchTerm)
	}

	// Otherwise check by name
	return (location.CustomName != nil && containsIgnoreCase(*location.CustomName, searchTerm)) ||
		(location.GooglePlaceName != nil && containsIgnoreCase(*location.GooglePlaceName, searchTerm))
}

// FilterTripsBySearch applies the in-memory filtering shared by every trip
// search path (SQL and Meilisearch):
//   - removes waypoints already passed;
//   - drops in-progress trips that have no pending waypoints left;
//   - keeps trips whose route origin/destination or a remaining waypoint
//     matches the given origin/destination terms (respecting waypoint order
//     when both match as waypoints);
//   - when skipInProgressOnDirectMatch is true (city-route search), in-progress
//     trips whose origin and destination both match directly are excluded.
func FilterTripsBySearch(trips []models.Trip, origin, destination, company string, skipInProgressOnDirectMatch bool) []models.Trip {
	filtered := make([]models.Trip, 0, len(trips))
	for _, trip := range trips {
		filteredWaypoints := make([]models.TripWaypoint, 0, len(trip.Waypoints))
		for _, wp := range trip.Waypoints {
			if wp.IsPassed {
				continue
			}
			filteredWaypoints = append(filteredWaypoints, wp)
		}

		hasUnpassedWaypoints := len(filteredWaypoints) > 0
		trip.Waypoints = filteredWaypoints

		// Only surface in-progress trips that still have pending waypoints
		if trip.Status == "IN_PROGRESS" && !hasUnpassedWaypoints {
			continue
		}

		// Company filter (already enforced in SQL; kept for the Meili path).
		// Empty CompanyName defers to the caller's pre-filter.
		if company != "" && trip.Vehicle.CompanyName != "" && !containsIgnoreCase(trip.Vehicle.CompanyName, company) {
			continue
		}

		matchOrigin := origin == ""
		matchDestination := destination == ""

		// Track waypoint order if both are found as waypoints
		originWaypointOrder := -1
		destinationWaypointOrder := -1

		if !matchOrigin {
			// Check route origin using enhanced search (name or code)
			if checkLocationMatch(&trip.Route.Origin, origin) {
				matchOrigin = true
			}
			// Check waypoints using enhanced search
			if !matchOrigin {
				for _, wp := range trip.Waypoints {
					if checkLocationMatch(&wp.Location, origin) {
						matchOrigin = true
						originWaypointOrder = wp.Order
						break
					}
				}
			}
		}

		if !matchDestination {
			// Check route destination using enhanced search (name or code)
			if checkLocationMatch(&trip.Route.Destination, destination) {
				matchDestination = true
			}
			// Check waypoints using enhanced search
			if !matchDestination {
				for _, wp := range trip.Waypoints {
					if checkLocationMatch(&wp.Location, destination) {
						matchDestination = true
						destinationWaypointOrder = wp.Order
						break
					}
				}
			}
		}

		// If both origin and destination are found as waypoints, check order
		if matchOrigin && matchDestination {
			if skipInProgressOnDirectMatch && trip.Status == "IN_PROGRESS" {
				continue
			}
			if originWaypointOrder != -1 && destinationWaypointOrder != -1 {
				if originWaypointOrder < destinationWaypointOrder {
					filtered = append(filtered, trip)
				}
			} else {
				// If not both are waypoints, keep current logic
				filtered = append(filtered, trip)
			}
		}
	}
	return filtered
}

// PaginateTrips slices an already-filtered (and sorted) trip slice into a page
// and returns the page alongside the total count of the full set.
func PaginateTrips(trips []models.Trip, limit, offset int) ([]models.Trip, int64) {
	total := int64(len(trips))
	if offset >= len(trips) {
		return []models.Trip{}, total
	}
	end := offset + limit
	if limit <= 0 || end > len(trips) {
		end = len(trips)
	}
	return trips[offset:end], total
}

// matchScore returns (score, position):
// score 3: whole field starts with query
// score 2: any word starts with query (position = word index)
// score 1: field contains query (position = index in field)
// score 0: no match
func matchScore(field, query string) (int, int) {
	fieldLower := strings.ToLower(field)
	queryLower := strings.ToLower(query)
	if strings.HasPrefix(fieldLower, queryLower) {
		return 3, 0
	}
	words := strings.Fields(fieldLower)
	for i, word := range words {
		if strings.HasPrefix(word, queryLower) {
			return 2, i
		}
	}
	if idx := strings.Index(fieldLower, queryLower); idx != -1 {
		return 1, idx
	}
	return 0, -1
}

// SortTripsByMatchScore sorts trips by the highest match score for origin, destination, or company, then by position, then stable
func SortTripsByMatchScore(trips []models.Trip, origin, destination, company string) []models.Trip {
	type scoredTrip struct {
		trip    models.Trip
		score   int
		pos     int
		origIdx int
	}
	var scored []scoredTrip
	for idx, trip := range trips {
		maxScore := 0
		minPos := 9999 // large number for min position
		// Company (from vehicle)
		if company != "" && trip.Vehicle.CompanyName != "" {
			s, p := matchScore(trip.Vehicle.CompanyName, company)
			if s > maxScore || (s == maxScore && p < minPos) {
				maxScore = s
				minPos = p
			}
		}
		// Origin
		if origin != "" {
			if trip.Route.Origin.CustomName != nil {
				s, p := matchScore(*trip.Route.Origin.CustomName, origin)
				if s > maxScore || (s == maxScore && p < minPos) {
					maxScore = s
					minPos = p
				}
			}
			if trip.Route.Origin.GooglePlaceName != nil {
				s, p := matchScore(*trip.Route.Origin.GooglePlaceName, origin)
				if s > maxScore || (s == maxScore && p < minPos) {
					maxScore = s
					minPos = p
				}
			}
			for _, wp := range trip.Waypoints {
				if wp.Location.CustomName != nil {
					s, p := matchScore(*wp.Location.CustomName, origin)
					if s > maxScore || (s == maxScore && p < minPos) {
						maxScore = s
						minPos = p
					}
				}
				if wp.Location.GooglePlaceName != nil {
					s, p := matchScore(*wp.Location.GooglePlaceName, origin)
					if s > maxScore || (s == maxScore && p < minPos) {
						maxScore = s
						minPos = p
					}
				}
			}
		}
		// Destination
		if destination != "" {
			if trip.Route.Destination.CustomName != nil {
				s, p := matchScore(*trip.Route.Destination.CustomName, destination)
				if s > maxScore || (s == maxScore && p < minPos) {
					maxScore = s
					minPos = p
				}
			}
			if trip.Route.Destination.GooglePlaceName != nil {
				s, p := matchScore(*trip.Route.Destination.GooglePlaceName, destination)
				if s > maxScore || (s == maxScore && p < minPos) {
					maxScore = s
					minPos = p
				}
			}
			for _, wp := range trip.Waypoints {
				if wp.Location.CustomName != nil {
					s, p := matchScore(*wp.Location.CustomName, destination)
					if s > maxScore || (s == maxScore && p < minPos) {
						maxScore = s
						minPos = p
					}
				}
				if wp.Location.GooglePlaceName != nil {
					s, p := matchScore(*wp.Location.GooglePlaceName, destination)
					if s > maxScore || (s == maxScore && p < minPos) {
						maxScore = s
						minPos = p
					}
				}
			}
		}
		scored = append(scored, scoredTrip{trip, maxScore, minPos, idx})
	}
	// Sort by score descending, then position ascending, then original order
	sort.SliceStable(scored, func(i, j int) bool {
		if scored[i].score != scored[j].score {
			return scored[i].score > scored[j].score
		}
		if scored[i].pos != scored[j].pos {
			return scored[i].pos < scored[j].pos
		}
		return scored[i].origIdx < scored[j].origIdx
	})
	// Extract sorted trips
	result := make([]models.Trip, len(scored))
	for i, st := range scored {
		result[i] = st.trip
	}
	return result
}

func (r *tripRepository) GetDriverMetrics(driverID int64) (*models.DriverMetrics, error) {
	var totalTrips int64
	var totalKilometers float64
	var dailyTrips int64
	var monthlyTrips int64
	var currentActiveTrip *int64

	// Get total trips count
	err := driverIDCondition(r.db.Model(&models.Trip{}), driverID).Count(&totalTrips).Error
	if err != nil {
		return nil, err
	}

	// Get total kilometers (sum of route distances for completed trips)
	err = driverIDCondition(r.db.Table("trips").
		Select("COALESCE(SUM(routes.distance_meters), 0)").
		Joins("JOIN routes ON trips.route_id = routes.id").
		Where("trips.status = ?", "COMPLETED"), driverID).
		Scan(&totalKilometers).Error
	if err != nil {
		return nil, err
	}

	// Get daily trips (trips created today)
	err = driverIDCondition(r.db.Model(&models.Trip{}).
		Where("DATE(created_at) = CURRENT_DATE"), driverID).
		Count(&dailyTrips).Error
	if err != nil {
		return nil, err
	}

	// Get monthly trips (trips created this month)
	err = driverIDCondition(r.db.Model(&models.Trip{}).
		Where("DATE_TRUNC('month', created_at) = DATE_TRUNC('month', CURRENT_DATE)"), driverID).
		Count(&monthlyTrips).Error
	if err != nil {
		return nil, err
	}

	// Get current active trip (SCHEDULED or IN_PROGRESS)
	var activeTripID int64
	err = driverIDCondition(r.db.Model(&models.Trip{}).
		Select("id").
		Where("status IN (?, ?)", "SCHEDULED", "IN_PROGRESS"), driverID).
		Order("created_at DESC").
		Limit(1).
		Scan(&activeTripID).Error
	if err != nil {
		return nil, err
	}

	if activeTripID > 0 {
		currentActiveTrip = &activeTripID
	}

	return &models.DriverMetrics{
		TotalTrips:        totalTrips,
		TotalKilometers:   totalKilometers,
		DailyTrips:        dailyTrips,
		MonthlyTrips:      monthlyTrips,
		CurrentActiveTrip: currentActiveTrip,
	}, nil
}

func (r *tripRepository) Delete(id int64) error {
	// First delete all waypoints associated with the trip
	if err := r.db.Where("trip_id = ?", id).Delete(&models.TripWaypoint{}).Error; err != nil {
		return err
	}

	// Then delete the trip itself
	return r.db.Delete(&models.Trip{}, id).Error
}
