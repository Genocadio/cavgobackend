package handlers

import (
	"cavgotrips/internal/models"
	"cavgotrips/internal/service"
	"cavgotrips/pkg/utils"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strconv"
	"time"

	"github.com/gorilla/mux"
)

type TripHandler struct {
	service   *service.TripService
	scheduler *service.TripUpdateScheduler
	poster    *service.TripUpdatePoster
	baseURL   string
}

func NewTripHandler(service *service.TripService, scheduler *service.TripUpdateScheduler, poster *service.TripUpdatePoster, baseURL string) *TripHandler {
	return &TripHandler{
		service:   service,
		scheduler: scheduler,
		poster:    poster,
		baseURL:   baseURL,
	}
}

func (h *TripHandler) CreateTrip(w http.ResponseWriter, r *http.Request) {
	var request models.CreateTripRequest
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
		utils.ErrorResponse(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	trip, err := h.service.CreateTrip(&request)
	if err != nil {
		var validationErr *models.ValidationError
		var conflictErr *models.ConflictError
		if errors.As(err, &validationErr) {
			utils.ErrorResponse(w, err.Error(), http.StatusBadRequest)
			return
		} else if errors.As(err, &conflictErr) {
			utils.ErrorResponse(w, err.Error(), http.StatusConflict)
			return
		} else {
			// For other errors, we return a generic internal server error
			utils.ErrorResponse(w, "Internal server error: "+err.Error(), http.StatusInternalServerError)
			return
		}
	}

	utils.JSONResponse(w, trip, http.StatusCreated)
}

func (h *TripHandler) GetTrip(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id, err := strconv.ParseInt(vars["id"], 10, 64)
	if err != nil {
		utils.ErrorResponse(w, "Invalid trip ID", http.StatusBadRequest)
		return
	}

	trip, err := h.service.GetTripByID(id)
	if err != nil {
		utils.ErrorResponse(w, "Trip not found", http.StatusNotFound)
		return
	}

	utils.JSONResponse(w, trip, http.StatusOK)
}

func (h *TripHandler) GetTrips(w http.ResponseWriter, r *http.Request) {
	// Check for query parameters
	status := r.URL.Query().Get("status")
	vehicleID := r.URL.Query().Get("vehicle_id")
	origin := r.URL.Query().Get("origin")
	destination := r.URL.Query().Get("destination")
	company := r.URL.Query().Get("company")
	cityRoute := r.URL.Query().Get("city_route")
	sessionUUID := r.URL.Query().Get("session_uuid") // New session parameter

	// Pagination params
	limitStr := r.URL.Query().Get("limit")
	offsetStr := r.URL.Query().Get("offset")
	limit := 20 // default page size
	offset := 0
	var err error
	if limitStr != "" {
		limit, err = strconv.Atoi(limitStr)
		if err != nil || limit < 0 {
			utils.ErrorResponse(w, "Invalid limit parameter", http.StatusBadRequest)
			return
		}
	}
	if offsetStr != "" {
		offset, err = strconv.Atoi(offsetStr)
		if err != nil || offset < 0 {
			utils.ErrorResponse(w, "Invalid offset parameter", http.StatusBadRequest)
			return
		}
	}

	var trips []models.Trip
	var total int64

	// New logic for cityRoute, origin, destination, company
	cityRoutePresent := cityRoute != ""
	originPresent := origin != ""
	destinationPresent := destination != ""
	companyPresent := company != ""

	if originPresent && destinationPresent {
		// Ignore cityRoute, filter by origin and destination (and company if present)
		trips, total, err = h.service.GetTripsByFiltersPaginated(origin, destination, company, limit, offset)
	} else if (originPresent && companyPresent && cityRoutePresent) || (destinationPresent && companyPresent && cityRoutePresent) {
		// Apply all three filters
		cityRouteBool, parseErr := strconv.ParseBool(cityRoute)
		if parseErr != nil {
			utils.ErrorResponse(w, "Invalid city_route parameter; must be 'true' or 'false'", http.StatusBadRequest)
			return
		}
		trips, total, err = h.service.GetTripsByFiltersWithCityRoute(origin, destination, company, &cityRouteBool, limit, offset)
	} else if (originPresent || destinationPresent || companyPresent) && cityRoutePresent {
		// Apply cityRoute in addition to the other filter(s)
		cityRouteBool, parseErr := strconv.ParseBool(cityRoute)
		if parseErr != nil {
			utils.ErrorResponse(w, "Invalid city_route parameter; must be 'true' or 'false'", http.StatusBadRequest)
			return
		}
		trips, total, err = h.service.GetTripsByFiltersWithCityRoute(origin, destination, company, &cityRouteBool, limit, offset)
	} else if cityRoutePresent {
		// Only cityRoute is present
		cityRouteBool, parseErr := strconv.ParseBool(cityRoute)
		if parseErr != nil {
			utils.ErrorResponse(w, "Invalid city_route parameter; must be 'true' or 'false'", http.StatusBadRequest)
			return
		}
		trips, err = h.service.GetTripsByCityRoute(cityRouteBool)
		total = int64(len(trips))
	} else if originPresent || destinationPresent || companyPresent {
		// Only origin, destination, or company (no cityRoute)
		trips, total, err = h.service.GetTripsByFiltersPaginated(origin, destination, company, limit, offset)
	} else if status != "" {
		trips, err = h.service.GetTripsByStatus(status)
		total = int64(len(trips))
	} else if vehicleID != "" {
		id, parseErr := strconv.ParseInt(vehicleID, 10, 64)
		if parseErr != nil {
			utils.ErrorResponse(w, "Invalid vehicle ID", http.StatusBadRequest)
			return
		}
		trips, err = h.service.GetTripsByVehicleID(id)
		if err != nil {
			utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
			return
		}
		total = int64(len(trips))
		// Apply pagination to vehicle trips
		if offset < len(trips) {
			end := offset + limit
			if end > len(trips) {
				end = len(trips)
			}
			trips = trips[offset:end]
		} else {
			trips = []models.Trip{}
		}
	} else {
		trips, total, err = h.service.GetTripsByFiltersPaginated("", "", "", limit, offset)
	}

	if err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// Extract trip IDs for session
	tripIDs := make([]int64, len(trips))
	for i, trip := range trips {
		tripIDs[i] = trip.ID
	}

	// Handle session logic
	var finalSessionUUID string
	var isNewSession bool

	if sessionUUID == "" {
		// Create new session for first page
		session := h.service.SessionService.CreateSession(tripIDs)
		finalSessionUUID = session.UUID
		isNewSession = true
	} else {
		// Try to update existing session with new trip IDs
		success := h.service.SessionService.UpdateSession(sessionUUID, tripIDs)
		if !success {
			// Session not found or expired, create new session instead of returning error
			log.Printf("[Trip] ⚠️ Session %s not found or expired, creating new session", sessionUUID)
			session := h.service.SessionService.CreateSession(tripIDs)
			finalSessionUUID = session.UUID
			isNewSession = true
		} else {
			finalSessionUUID = sessionUUID
			isNewSession = false
		}
	}

	// Calculate page number from offset (1-based)
	page := 1
	if limit > 0 {
		page = (offset / limit) + 1
	}

	// Calculate total pages
	totalPages := 0
	if limit > 0 {
		totalPages = int((total + int64(limit) - 1) / int64(limit))
	}

	// Create page response with session data
	pageResponse := models.PageResponse{
		Trips:      trips,
		Total:      total,
		Limit:      limit,
		Offset:     offset,
		Page:       page,
		TotalPages: totalPages,
	}

	// Only include session UUID if it's a new session
	if isNewSession {
		pageResponse.SSEUUID = finalSessionUUID
	}

	utils.JSONResponse(w, pageResponse, http.StatusOK)
}

func (h *TripHandler) StartTrip(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id, err := strconv.ParseInt(vars["id"], 10, 64)
	if err != nil {
		utils.ErrorResponse(w, "Invalid trip ID", http.StatusBadRequest)
		return
	}

	trip, err := h.service.StartTrip(id)
	if err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusBadRequest)
		return
	}

	utils.JSONResponse(w, trip, http.StatusOK)
}

func (h *TripHandler) CompleteTrip(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id, err := strconv.ParseInt(vars["id"], 10, 64)
	if err != nil {
		utils.ErrorResponse(w, "Invalid trip ID", http.StatusBadRequest)
		return
	}

	trip, err := h.service.CompleteTrip(id)
	if err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusBadRequest)
		return
	}

	utils.JSONResponse(w, trip, http.StatusOK)
}

func (h *TripHandler) UpdateTripProgress(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id, err := strconv.ParseInt(vars["id"], 10, 64)
	if err != nil {
		utils.ErrorResponse(w, "Invalid trip ID", http.StatusBadRequest)
		return
	}

	var updateData models.TripProgressUpdate
	if err := json.NewDecoder(r.Body).Decode(&updateData); err != nil {
		utils.ErrorResponse(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	trip, err := h.service.UpdateTripProgress(id, &updateData)
	if err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.JSONResponse(w, trip, http.StatusOK)
}

func (h *TripHandler) GetTripProgress(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id, err := strconv.ParseInt(vars["id"], 10, 64)
	if err != nil {
		utils.ErrorResponse(w, "Invalid trip ID", http.StatusBadRequest)
		return
	}

	progress, err := h.service.GetTripProgress(id)
	if err != nil {
		utils.ErrorResponse(w, "Trip not found", http.StatusNotFound)
		return
	}

	utils.JSONResponse(w, progress, http.StatusOK)
}

// GetTripsByVehicleID gets all trips for a specific vehicle
func (h *TripHandler) GetTripsByVehicleID(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	vehicleID, err := strconv.ParseInt(vars["vehicle_id"], 10, 64)
	if err != nil {
		utils.ErrorResponse(w, "Invalid vehicle ID", http.StatusBadRequest)
		return
	}

	// Check for optional query parameters
	status := r.URL.Query().Get("status")
	limitStr := r.URL.Query().Get("limit")
	offsetStr := r.URL.Query().Get("offset")

	limit := 20 // default page size
	offset := 0

	if limitStr != "" {
		limit, err = strconv.Atoi(limitStr)
		if err != nil || limit < 0 {
			utils.ErrorResponse(w, "Invalid limit parameter", http.StatusBadRequest)
			return
		}
	}

	if offsetStr != "" {
		offset, err = strconv.Atoi(offsetStr)
		if err != nil || offset < 0 {
			utils.ErrorResponse(w, "Invalid offset parameter", http.StatusBadRequest)
			return
		}
	}

	var trips []models.Trip
	var total int64

	if status != "" {
		// Filter by both vehicle ID and status
		allTrips, err := h.service.GetTripsByVehicleID(vehicleID)
		if err != nil {
			utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
			return
		}

		// Filter by status in memory
		filteredTrips := make([]models.Trip, 0)
		for _, trip := range allTrips {
			if trip.Status == status {
				filteredTrips = append(filteredTrips, trip)
			}
		}

		// Set total to the count of filtered trips (before pagination)
		total = int64(len(filteredTrips))

		// Apply pagination
		if offset < len(filteredTrips) {
			end := offset + limit
			if end > len(filteredTrips) {
				end = len(filteredTrips)
			}
			trips = filteredTrips[offset:end]
		} else {
			trips = []models.Trip{}
		}
	} else {
		// Just get all trips for the vehicle
		allTrips, err := h.service.GetTripsByVehicleID(vehicleID)
		if err != nil {
			utils.ErrorResponse(w, "Invalid vehicle ID", http.StatusInternalServerError)
			return
		}

		// Set total to the count of all trips (before pagination)
		total = int64(len(allTrips))

		// Apply pagination
		if offset < len(allTrips) {
			end := offset + limit
			if end > len(allTrips) {
				end = len(allTrips)
			}
			trips = allTrips[offset:end]
		} else {
			trips = []models.Trip{}
		}
	}

	// Extract trip IDs for session
	tripIDs := make([]int64, len(trips))
	for i, trip := range trips {
		tripIDs[i] = trip.ID
	}

	// Handle session logic
	sessionUUID := r.URL.Query().Get("session_uuid")
	var finalSessionUUID string
	var isNewSession bool

	if sessionUUID == "" {
		// Create new session for first page
		session := h.service.SessionService.CreateSession(tripIDs)
		finalSessionUUID = session.UUID
		isNewSession = true
	} else {
		// Try to update existing session with new trip IDs
		success := h.service.SessionService.UpdateSession(sessionUUID, tripIDs)
		if !success {
			// Session not found or expired, create new session instead of returning error
			log.Printf("[Trip] ⚠️ Session %s not found or expired, creating new session", sessionUUID)
			session := h.service.SessionService.CreateSession(tripIDs)
			finalSessionUUID = session.UUID
			isNewSession = true
		} else {
			finalSessionUUID = sessionUUID
			isNewSession = false
		}
	}

	// Calculate page number from offset (1-based)
	page := 1
	if limit > 0 {
		page = (offset / limit) + 1
	}

	// Calculate total pages
	totalPages := 0
	if limit > 0 {
		totalPages = int((total + int64(limit) - 1) / int64(limit))
	}

	// Create page response with session data
	pageResponse := models.PageResponse{
		Trips:      trips,
		Total:      total,
		Limit:      limit,
		Offset:     offset,
		Page:       page,
		TotalPages: totalPages,
	}

	// Only include session UUID if it's a new session
	if isNewSession {
		pageResponse.SSEUUID = finalSessionUUID
	}

	utils.JSONResponse(w, pageResponse, http.StatusOK)
}

// GetTripsByDriverID gets all trips for a specific driver
func (h *TripHandler) GetTripsByDriverID(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	driverID, err := strconv.ParseInt(vars["driver_id"], 10, 64)
	if err != nil {
		utils.ErrorResponse(w, "Invalid driver ID", http.StatusBadRequest)
		return
	}

	// Check for optional query parameters
	status := r.URL.Query().Get("status")
	limitStr := r.URL.Query().Get("limit")
	offsetStr := r.URL.Query().Get("offset")

	limit := 20 // default page size
	offset := 0

	if limitStr != "" {
		limit, err = strconv.Atoi(limitStr)
		if err != nil || limit < 0 {
			utils.ErrorResponse(w, "Invalid limit parameter", http.StatusBadRequest)
			return
		}
	}

	if offsetStr != "" {
		offset, err = strconv.Atoi(offsetStr)
		if err != nil || offset < 0 {
			utils.ErrorResponse(w, "Invalid offset parameter", http.StatusBadRequest)
			return
		}
	}

	var trips []models.Trip
	var total int64

	if status != "" {
		// Filter by both driver ID and status
		allTrips, err := h.service.GetTripsByDriverID(driverID)
		if err != nil {
			utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
			return
		}

		// Filter by status in memory
		filteredTrips := make([]models.Trip, 0)
		for _, trip := range allTrips {
			if trip.Status == status {
				filteredTrips = append(filteredTrips, trip)
			}
		}

		// Set total to the count of filtered trips (before pagination)
		total = int64(len(filteredTrips))

		// Apply pagination
		if offset < len(filteredTrips) {
			end := offset + limit
			if end > len(filteredTrips) {
				end = len(filteredTrips)
			}
			trips = filteredTrips[offset:end]
		} else {
			trips = []models.Trip{}
		}
	} else {
		// Just get all trips for the driver
		allTrips, err := h.service.GetTripsByDriverID(driverID)
		if err != nil {
			utils.ErrorResponse(w, "Invalid driver ID", http.StatusInternalServerError)
			return
		}

		// Set total to the count of all trips (before pagination)
		total = int64(len(allTrips))

		// Apply pagination
		if offset < len(allTrips) {
			end := offset + limit
			if end > len(allTrips) {
				end = len(allTrips)
			}
			trips = allTrips[offset:end]
		} else {
			trips = []models.Trip{}
		}
	}

	// Extract trip IDs for session
	tripIDs := make([]int64, len(trips))
	for i, trip := range trips {
		tripIDs[i] = trip.ID
	}

	// Handle session logic
	sessionUUID := r.URL.Query().Get("session_uuid")
	var finalSessionUUID string
	var isNewSession bool

	if sessionUUID == "" {
		// Create new session for first page
		session := h.service.SessionService.CreateSession(tripIDs)
		finalSessionUUID = session.UUID
		isNewSession = true
	} else {
		// Try to update existing session with new trip IDs
		success := h.service.SessionService.UpdateSession(sessionUUID, tripIDs)
		if !success {
			// Session not found or expired, create new session instead of returning error
			log.Printf("[Trip] ⚠️ Session %s not found or expired, creating new session", sessionUUID)
			session := h.service.SessionService.CreateSession(tripIDs)
			finalSessionUUID = session.UUID
			isNewSession = true
		} else {
			finalSessionUUID = sessionUUID
			isNewSession = false
		}
	}

	// Get driver metrics
	metrics, err := h.service.GetDriverMetrics(driverID)
	if err != nil {
		log.Printf("[Trip] Failed to get driver metrics for driver %d: %v", driverID, err)
		// Don't fail the entire request, just log the error and continue without metrics
		metrics = &models.DriverMetrics{}
	}

	// Create driver trips response with metrics
	driverResponse := models.DriverTripsResponse{
		Trips:   trips,
		Total:   total,
		Limit:   limit,
		Offset:  offset,
		Metrics: *metrics,
	}

	// Only include session UUID if it's a new session
	if isNewSession {
		driverResponse.SSEUUID = finalSessionUUID
	}

	utils.JSONResponse(w, driverResponse, http.StatusOK)
}

func (h *TripHandler) DeleteTrip(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id, err := strconv.ParseInt(vars["id"], 10, 64)
	if err != nil {
		utils.ErrorResponse(w, "Invalid trip ID", http.StatusBadRequest)
		return
	}

	err = h.service.DeleteTrip(id)
	if err != nil {
		if err.Error() == "trip not found" {
			utils.ErrorResponse(w, "Trip not found", http.StatusNotFound)
			return
		} else if err.Error() == "cannot delete completed or not-completed trips" {
			utils.ErrorResponse(w, err.Error(), http.StatusBadRequest)
			return
		} else {
			utils.ErrorResponse(w, "Internal server error: "+err.Error(), http.StatusInternalServerError)
			return
		}
	}

	utils.JSONResponse(w, map[string]string{"message": "Trip processed successfully"}, http.StatusOK)
}

// GetTripLogs retrieves all logs for a specific trip
func (h *TripHandler) GetTripLogs(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id, err := strconv.ParseInt(vars["id"], 10, 64)
	if err != nil {
		utils.ErrorResponse(w, "Invalid trip ID", http.StatusBadRequest)
		return
	}

	logs, err := h.service.GetTripLogs(id)
	if err != nil {
		utils.ErrorResponse(w, "Failed to retrieve trip logs: "+err.Error(), http.StatusInternalServerError)
		return
	}

	if logs == nil {
		// Logging is disabled or no logs found
		utils.JSONResponse(w, []models.TripLog{}, http.StatusOK)
		return
	}

	utils.JSONResponse(w, logs, http.StatusOK)
}

// GetTripsByCompanyID gets trips for a specific company (internal endpoint)
func (h *TripHandler) GetTripsByCompanyID(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	companyID, err := strconv.ParseInt(vars["company_id"], 10, 64)
	if err != nil {
		utils.ErrorResponse(w, "Invalid company ID", http.StatusBadRequest)
		return
	}

	// Parse optional query parameters
	var driverID *int64
	var vehicleID *int64
	var fromDate *time.Time
	var afterTripID *int64

	if driverIDStr := r.URL.Query().Get("driver_id"); driverIDStr != "" {
		id, err := strconv.ParseInt(driverIDStr, 10, 64)
		if err != nil {
			utils.ErrorResponse(w, "Invalid driver ID", http.StatusBadRequest)
			return
		}
		driverID = &id
	}

	if vehicleIDStr := r.URL.Query().Get("vehicle_id"); vehicleIDStr != "" {
		id, err := strconv.ParseInt(vehicleIDStr, 10, 64)
		if err != nil {
			utils.ErrorResponse(w, "Invalid vehicle ID", http.StatusBadRequest)
			return
		}
		vehicleID = &id
	}

	if fromDateStr := r.URL.Query().Get("from_date"); fromDateStr != "" {
		date, err := time.Parse("2006-01-02", fromDateStr)
		if err != nil {
			utils.ErrorResponse(w, "Invalid from_date format (expected YYYY-MM-DD)", http.StatusBadRequest)
			return
		}
		fromDate = &date
	}

	if afterTripIDStr := r.URL.Query().Get("trip_id"); afterTripIDStr != "" {
		id, err := strconv.ParseInt(afterTripIDStr, 10, 64)
		if err != nil {
			utils.ErrorResponse(w, "Invalid trip_id parameter", http.StatusBadRequest)
			return
		}
		afterTripID = &id
	}

	// Parse pagination parameters
	limitStr := r.URL.Query().Get("limit")
	offsetStr := r.URL.Query().Get("offset")
	limit := 20 // default page size
	offset := 0

	if limitStr != "" {
		limit, err = strconv.Atoi(limitStr)
		if err != nil || limit < 0 {
			utils.ErrorResponse(w, "Invalid limit parameter", http.StatusBadRequest)
			return
		}
	}

	if offsetStr != "" {
		offset, err = strconv.Atoi(offsetStr)
		if err != nil || offset < 0 {
			utils.ErrorResponse(w, "Invalid offset parameter", http.StatusBadRequest)
			return
		}
	}

	// Get trips from service
	trips, total, err := h.service.GetTripsByCompanyID(companyID, driverID, vehicleID, fromDate, afterTripID, limit, offset)
	if err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// Extract trip IDs for timer management
	tripIDs := make([]int64, len(trips))
	for i, trip := range trips {
		tripIDs[i] = trip.ID
	}

	// Start or extend timer if scheduler and poster are available
	if h.scheduler != nil && h.poster != nil && h.baseURL != "" {
		h.scheduler.StartOrExtendTimer(companyID, h.baseURL, tripIDs)
	}

	// Calculate page number from offset (1-based)
	page := 1
	if limit > 0 {
		page = (offset / limit) + 1
	}

	// Calculate total pages
	totalPages := 0
	if limit > 0 {
		totalPages = int((total + int64(limit) - 1) / int64(limit))
	}

	// Create paginated response
	pageResponse := models.PageResponse{
		Trips:      trips,
		Total:      total,
		Limit:      limit,
		Offset:     offset,
		Page:       page,
		TotalPages: totalPages,
	}

	utils.JSONResponse(w, pageResponse, http.StatusOK)
}

// GetInternalTrips gets all trips from last 30 days (internal endpoint)
func (h *TripHandler) GetInternalTrips(w http.ResponseWriter, r *http.Request) {
	// Parse optional last_update_time parameter
	var lastUpdateTime *time.Time
	if lastUpdateTimeStr := r.URL.Query().Get("last_update_time"); lastUpdateTimeStr != "" {
		// Try multiple timestamp formats to handle various ISO 8601 formats
		var parsedTime time.Time
		var err error
		
		// Try custom format with milliseconds first (e.g., 2025-11-14T09:17:51.628Z)
		parsedTime, err = time.Parse("2006-01-02T15:04:05.000Z", lastUpdateTimeStr)
		if err != nil {
			// Try RFC3339 format (standard ISO 8601 with optional fractional seconds)
			parsedTime, err = time.Parse(time.RFC3339, lastUpdateTimeStr)
			if err != nil {
				// Try RFC3339Nano (for nanosecond precision)
				parsedTime, err = time.Parse(time.RFC3339Nano, lastUpdateTimeStr)
				if err != nil {
					utils.ErrorResponse(w, "Invalid last_update_time format (expected RFC3339, e.g., 2025-11-14T09:17:51.628Z)", http.StatusBadRequest)
					return
				}
			}
		}
		lastUpdateTime = &parsedTime
	}

	// Parse pagination parameters
	limitStr := r.URL.Query().Get("limit")
	offsetStr := r.URL.Query().Get("offset")
	limit := 20 // default page size
	offset := 0

	if limitStr != "" {
		var err error
		limit, err = strconv.Atoi(limitStr)
		if err != nil || limit < 0 {
			utils.ErrorResponse(w, "Invalid limit parameter", http.StatusBadRequest)
			return
		}
	}

	if offsetStr != "" {
		var err error
		offset, err = strconv.Atoi(offsetStr)
		if err != nil || offset < 0 {
			utils.ErrorResponse(w, "Invalid offset parameter", http.StatusBadRequest)
			return
		}
	}

	// Get trips from service
	trips, total, err := h.service.GetAllTripsInternal(lastUpdateTime, limit, offset)
	if err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// Calculate page number from offset (1-based)
	page := 1
	if limit > 0 {
		page = (offset / limit) + 1
	}

	// Calculate total pages
	totalPages := 0
	if limit > 0 {
		totalPages = int((total + int64(limit) - 1) / int64(limit))
	}

	// Create paginated response
	pageResponse := models.PageResponse{
		Trips:      trips,
		Total:      total,
		Limit:      limit,
		Offset:     offset,
		Page:       page,
		TotalPages: totalPages,
	}

	utils.JSONResponse(w, pageResponse, http.StatusOK)
}
