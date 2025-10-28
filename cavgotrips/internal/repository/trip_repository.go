package repository

import (
	"cavgotrips/internal/models"
	"sort"
	"strconv"
	"strings"

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
	db := r.db.Preload("Route.Origin").Preload("Route.Destination").Preload("Waypoints.Location").Order("created_at DESC")

	if company != "" {
		db = db.Where("LOWER(trips.vehicle->>'company_name') LIKE ?", "%"+strings.ToLower(company)+"%")
	}

	err := db.Find(&trips).Error
	if err != nil {
		return nil, err
	}

	// Remove waypoints where is_passed is true from each trip
	for i := range trips {
		filteredWaypoints := make([]models.TripWaypoint, 0, len(trips[i].Waypoints))
		for _, wp := range trips[i].Waypoints {
			if !wp.IsPassed {
				filteredWaypoints = append(filteredWaypoints, wp)
			}
		}
		trips[i].Waypoints = filteredWaypoints
	}

	// Filter by origin/destination in Go (route or any non-passed waypoint)
	filteredTrips := make([]models.Trip, 0, len(trips))
	for _, trip := range trips {
		matchOrigin := false
		matchDestination := false

		// Track waypoint order if both are found as waypoints
		originWaypointOrder := -1
		destinationWaypointOrder := -1

		if origin == "" {
			matchOrigin = true
		} else {
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

		if destination == "" {
			matchDestination = true
		} else {
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
			if originWaypointOrder != -1 && destinationWaypointOrder != -1 {
				if originWaypointOrder < destinationWaypointOrder {
					filteredTrips = append(filteredTrips, trip)
				}
			} else {
				// If not both are waypoints, keep current logic
				filteredTrips = append(filteredTrips, trip)
			}
		}
	}

	filteredTrips = sortTripsByMatchScore(filteredTrips, origin, destination, company)

	return filteredTrips, nil
}

func (r *tripRepository) GetTripsByFiltersPaginated(origin, destination, company string, limit, offset int) ([]models.Trip, int64, error) {
	var trips []models.Trip
	db := r.db.Preload("Route.Origin").Preload("Route.Destination").Preload("Waypoints.Location").Order("created_at DESC")

	if company != "" {
		db = db.Where("LOWER(trips.vehicle->>'company_name') LIKE ?", "%"+strings.ToLower(company)+"%")
	}

	var total int64
	db.Model(&models.Trip{}).Count(&total)

	if limit > 0 {
		db = db.Limit(limit)
	}
	if offset > 0 {
		db = db.Offset(offset)
	}

	err := db.Find(&trips).Error
	if err != nil {
		return nil, 0, err
	}

	// Remove waypoints where is_passed is true from each trip
	for i := range trips {
		filteredWaypoints := make([]models.TripWaypoint, 0, len(trips[i].Waypoints))
		for _, wp := range trips[i].Waypoints {
			if !wp.IsPassed {
				filteredWaypoints = append(filteredWaypoints, wp)
			}
		}
		trips[i].Waypoints = filteredWaypoints
	}

	// Filter by origin/destination in Go (route or any non-passed waypoint)
	filteredTrips := make([]models.Trip, 0, len(trips))
	for _, trip := range trips {
		matchOrigin := false
		matchDestination := false

		// Track waypoint order if both are found as waypoints
		originWaypointOrder := -1
		destinationWaypointOrder := -1

		if origin == "" {
			matchOrigin = true
		} else {
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

		if destination == "" {
			matchDestination = true
		} else {
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
			if originWaypointOrder != -1 && destinationWaypointOrder != -1 {
				if originWaypointOrder < destinationWaypointOrder {
					filteredTrips = append(filteredTrips, trip)
				}
			} else {
				// If not both are waypoints, keep current logic
				filteredTrips = append(filteredTrips, trip)
			}
		}
	}

	filteredTrips = sortTripsByMatchScore(filteredTrips, origin, destination, company)

	return filteredTrips, total, nil
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

func (r *tripRepository) GetTripsByDriverID(driverID int64) ([]models.Trip, error) {
	var trips []models.Trip
	err := driverIDCondition(r.db.Preload("Route.Origin").
		Preload("Route.Destination").
		Preload("Waypoints.Location"), driverID).
		Order("created_at DESC").
		Find(&trips).Error
	return trips, err
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
		Order("trips.created_at DESC")

	if company != "" {
		db = db.Where("LOWER(trips.vehicle->>'company_name') LIKE ?", "%"+strings.ToLower(company)+"%")
	}

	var total int64
	db.Model(&models.Trip{}).Count(&total)

	if limit > 0 {
		db = db.Limit(limit)
	}
	if offset > 0 {
		db = db.Offset(offset)
	}

	err := db.Find(&trips).Error
	if err != nil {
		return nil, 0, err
	}

	// Remove waypoints where is_passed is true from each trip
	for i := range trips {
		filteredWaypoints := make([]models.TripWaypoint, 0, len(trips[i].Waypoints))
		for _, wp := range trips[i].Waypoints {
			if !wp.IsPassed {
				filteredWaypoints = append(filteredWaypoints, wp)
			}
		}
		trips[i].Waypoints = filteredWaypoints
	}

	// Filter by origin/destination in Go (route or any non-passed waypoint)
	filteredTrips := make([]models.Trip, 0, len(trips))
	for _, trip := range trips {
		matchOrigin := false
		matchDestination := false

		// Track waypoint order if both are found as waypoints
		originWaypointOrder := -1
		destinationWaypointOrder := -1

		if origin == "" {
			matchOrigin = true
		} else {
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

		if destination == "" {
			matchDestination = true
		} else {
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
			if originWaypointOrder != -1 && destinationWaypointOrder != -1 {
				if originWaypointOrder < destinationWaypointOrder {
					filteredTrips = append(filteredTrips, trip)
				}
			} else {
				// If not both are waypoints, keep current logic
				filteredTrips = append(filteredTrips, trip)
			}
		}
	}

	filteredTrips = sortTripsByMatchScore(filteredTrips, origin, destination, company)

	return filteredTrips, int64(len(filteredTrips)), nil
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

// sortTripsByMatchScore sorts trips by the highest match score for origin, destination, or company, then by position, then stable
func sortTripsByMatchScore(trips []models.Trip, origin, destination, company string) []models.Trip {
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
