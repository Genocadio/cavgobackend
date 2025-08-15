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

	"github.com/gorilla/mux"
)

type TripHandler struct {
	service *service.TripService
}

func NewTripHandler(service *service.TripService) *TripHandler {
	return &TripHandler{service: service}
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
		total = int64(len(trips))
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

	// Create page response with session data
	pageResponse := models.PageResponse{
		Trips:  trips,
		Total:  total,
		Limit:  limit,
		Offset: offset,
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

		trips = filteredTrips
		total = int64(len(trips))

		// Apply pagination
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
		// Just get all trips for the vehicle
		allTrips, err := h.service.GetTripsByVehicleID(vehicleID)
		if err != nil {
			utils.ErrorResponse(w, "Invalid vehicle ID", http.StatusInternalServerError)
			return
		}

		total = int64(len(allTrips))

		// Apply pagination
		if offset < len(trips) {
			end := offset + limit
			if end > len(trips) {
				end = len(trips)
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

	// Create page response with session data
	pageResponse := models.PageResponse{
		Trips:  trips,
		Total:  total,
		Limit:  limit,
		Offset: offset,
	}

	// Only include session UUID if it's a new session
	if isNewSession {
		pageResponse.SSEUUID = finalSessionUUID
	}

	utils.JSONResponse(w, pageResponse, http.StatusOK)
}
