package service

import (
	"cavgotrips/internal/models"
	"cavgotrips/internal/repository"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"time"
)

type TripService struct {
	tripRepo          repository.TripRepository
	routeRepo         repository.RouteRepository
	locationRepo      repository.LocationRepository
	vehicleServiceURL string
	rabbitMQService   *RabbitMQService // Add RabbitMQService
	sseService        *SSEService      // Add SSE service
	SessionService    *SessionService  // Add Session service
}

func NewTripService(tripRepo repository.TripRepository, routeRepo repository.RouteRepository, locationRepo repository.LocationRepository, vehicleServiceURL string, rabbitMQService *RabbitMQService, sseService *SSEService, sessionService *SessionService) *TripService {
	return &TripService{
		tripRepo:          tripRepo,
		routeRepo:         routeRepo,
		locationRepo:      locationRepo,
		vehicleServiceURL: vehicleServiceURL,
		rabbitMQService:   rabbitMQService,
		sseService:        sseService,
		SessionService:    sessionService,
	}
}

func (s *TripService) CreateTrip(request *models.CreateTripRequest) (*models.Trip, error) {
	// Validate request fields before proceeding
	if err := request.Validate(); err != nil {
		return nil, models.NewValidationError(err.Error())
	}
	// Get the route to copy waypoints
	route, err := s.routeRepo.GetByIDWithWaypoints(request.RouteID)
	if err != nil {
		return nil, models.NewValidationError("route not found")
	}

	// Fetch vehicle data from vehicle service
	vehicleURL := fmt.Sprintf("%s%d", s.vehicleServiceURL, request.VehicleID)
	fmt.Printf("[DEBUG] Vehicle service URL: %s\n", vehicleURL)
	resp, err := http.Get(vehicleURL)
	if err != nil {
		return nil, models.NewValidationError("vehicle service unavailable: " + err.Error())
	}
	fmt.Printf("[DEBUG] Vehicle service response status: %d\n", resp.StatusCode)
	defer resp.Body.Close()
	// Read and log the response body for debugging
	var rawBody []byte
	rawBody, err = io.ReadAll(resp.Body)
	if err != nil {
		return nil, models.NewValidationError("failed to read vehicle response body: " + err.Error())
	}
	fmt.Printf("[DEBUG] Vehicle service response body: %s\n", string(rawBody))
	if resp.StatusCode != http.StatusOK {
		return nil, models.NewValidationError("vehicle not found (status code: " + strconv.Itoa(resp.StatusCode) + ")")
	}
	// Decode from the rawBody instead of resp.Body (since it's already read)
	var vehicleResp struct {
		ID           int64  `json:"id"`
		CompanyID    int64  `json:"companyId"`
		CompanyName  string `json:"companyName"`
		Capacity     int    `json:"capacity"`
		LicensePlate string `json:"licensePlate"`
		Driver       *struct {
			FirstName string `json:"firstName"`
			LastName  string `json:"lastName"`
			Phone     string `json:"phone"`
		} `json:"driver"`
		Status string `json:"status"` // Add status field
	}
	if err := json.Unmarshal(rawBody, &vehicleResp); err != nil {
		return nil, models.NewValidationError("invalid vehicle response: " + err.Error())
	}

	// Check vehicle status
	if vehicleResp.Status != "AVAILABLE" {
		return nil, models.NewValidationError("vehicle is not available")
	}

	// Check for existing active trips for this vehicle
	currentTrips, err := s.tripRepo.GetTripsByVehicleID(request.VehicleID)
	if err != nil {
		return nil, models.NewValidationError("could not check vehicle's current trips")
	}
	for _, t := range currentTrips {
		if t.Status == "SCHEDULED" || t.Status == "IN_PROGRESS" {
			return nil, models.NewValidationError("vehicle already has an active trip")
		}
	}

	driverName := ""
	driverPhone := ""
	if vehicleResp.Driver != nil {
		driverName = fmt.Sprintf("%s %s", vehicleResp.Driver.FirstName, vehicleResp.Driver.LastName)
		driverPhone = vehicleResp.Driver.Phone
	}

	vehicle := models.Vehicle{
		ID:           vehicleResp.ID,
		CompanyID:    vehicleResp.CompanyID,
		CompanyName:  vehicleResp.CompanyName,
		Capacity:     vehicleResp.Capacity,
		LicensePlate: vehicleResp.LicensePlate,
		Driver: models.DriverSnapshot{
			Name:  driverName,
			Phone: driverPhone,
		},
	}

	// Create the trip
	trip := &models.Trip{
		RouteID:            request.RouteID,
		VehicleID:          request.VehicleID,
		Vehicle:            vehicle,
		Status:             "SCHEDULED",
		DepartureTime:      request.DepartureTime,
		ConnectionMode:     request.ConnectionMode,
		Notes:              request.Notes,
		Seats:              vehicle.Capacity,
		IsReversed:         request.IsReversed,
		HasCustomWaypoints: !request.NoWaypoints && len(request.CustomWaypoints) > 0,
	}

	// Validate trip
	if err := trip.Validate(); err != nil {
		return nil, models.NewValidationError(err.Error())
	}

	// Create trip in database
	if err := s.tripRepo.Create(trip); err != nil {
		return nil, err
	}

	// Create waypoints based on the request parameters
	if request.NoWaypoints {
		// No waypoints - only origin and destination from route
		// This creates a simple point-to-point trip without intermediate stops
	} else if len(request.CustomWaypoints) > 0 {
		// Use custom waypoints
		for _, customWaypoint := range request.CustomWaypoints {
			// Validate location exists
			if err := s.locationRepo.ValidateExists(customWaypoint.LocationID); err != nil {
				return nil, models.NewValidationError("invalid location in custom waypoints " + strconv.FormatInt(customWaypoint.LocationID, 10))
			}

			tripWaypoint := &models.TripWaypoint{
				TripID:            trip.ID,
				LocationID:        customWaypoint.LocationID,
				Order:             customWaypoint.Order,
				Price:             customWaypoint.Price,
				RemainingTime:     customWaypoint.RemainingTime,
				RemainingDistance: customWaypoint.RemainingDistance,
				IsCustom:          true,
			}

			if err := s.tripRepo.CreateWaypoint(tripWaypoint); err != nil {
				return nil, err
			}
		}
	} else {
		// Use route waypoints
		waypoints := route.Waypoints
		if request.IsReversed {
			// Reverse the waypoints
			for i := len(waypoints) - 1; i >= 0; i-- {
				routeWaypoint := waypoints[i]
				tripWaypoint := &models.TripWaypoint{
					TripID:     trip.ID,
					LocationID: routeWaypoint.LocationID,
					Order:      len(waypoints) - i,
					Price:      &routeWaypoint.Price,
					IsCustom:   false,
				}

				if err := s.tripRepo.CreateWaypoint(tripWaypoint); err != nil {
					return nil, err
				}
			}
		} else {
			// Normal order
			for _, routeWaypoint := range waypoints {
				tripWaypoint := &models.TripWaypoint{
					TripID:     trip.ID,
					LocationID: routeWaypoint.LocationID,
					Order:      routeWaypoint.Order,
					Price:      &routeWaypoint.Price,
					IsCustom:   false,
				}

				if err := s.tripRepo.CreateWaypoint(tripWaypoint); err != nil {
					return nil, err
				}
			}
		}
	}

	// Return the complete trip with relationships but without route waypoints
	createdTrip, err := s.tripRepo.GetByIDWithRelations(trip.ID)
	if err != nil {
		return nil, err
	}

	createdTrip.Route.Waypoints = nil

	// Publish event to RabbitMQ
	if s.rabbitMQService != nil {
		_ = s.rabbitMQService.PublishTripEvent("created", *createdTrip)
	}

	// Broadcast SSE event
	if s.sseService != nil {
		s.sseService.BroadcastTripEventToSessions(models.TripEventMessage{
			Event: "created",
			Data:  *createdTrip,
		})
	}

	return createdTrip, nil
}

func (s *TripService) UpdateTripProgress(id int64, update *models.TripProgressUpdate) (*models.Trip, error) {
	// Get current trip
	trip, err := s.tripRepo.GetByIDWithRelations(id)
	if err != nil {
		return nil, errors.New("trip not found")
	}

	// Only allow updates for trips that have started
	if trip.Status == "SCHEDULED" && update.Status != nil && *update.Status != "IN_PROGRESS" {
		return nil, errors.New("trip must be started before updating progress")
	}

	// Update trip fields
	updates := make(map[string]interface{})
	if update.Status != nil {
		updates["status"] = *update.Status
		updates["updated_at"] = time.Now()
	}
	if update.RemainingTimeToDestination != nil {
		updates["remaining_time_to_destination"] = *update.RemainingTimeToDestination
	}
	if update.RemainingDistanceToDestination != nil {
		updates["remaining_distance_to_destination"] = *update.RemainingDistanceToDestination
	}
	if update.CurrentSpeed != nil {
		updates["current_speed"] = *update.CurrentSpeed
	}
	if update.CurrentLatitude != nil {
		updates["current_latitude"] = *update.CurrentLatitude
	}
	if update.CurrentLongitude != nil {
		updates["current_longitude"] = *update.CurrentLongitude
	}
	if update.CompletionTime != nil {
		updates["completion_time"] = *update.CompletionTime
	}

	if len(updates) > 0 {
		if err := s.tripRepo.UpdateProgress(id, updates); err != nil {
			return nil, err
		}
	}

	// Update waypoint progress
	if len(update.WaypointUpdates) > 0 {
		for _, waypointUpdate := range update.WaypointUpdates {
			waypointUpdates := make(map[string]interface{})
			if waypointUpdate.RemainingTime != nil {
				waypointUpdates["remaining_time"] = *waypointUpdate.RemainingTime
			}
			if waypointUpdate.RemainingDistance != nil {
				waypointUpdates["remaining_distance"] = *waypointUpdate.RemainingDistance
			}
			waypointUpdates["updated_at"] = time.Now()

			if err := s.tripRepo.UpdateWaypointProgress(waypointUpdate.WaypointID, waypointUpdates); err != nil {
				return nil, err
			}
		}
	}

	// Mark waypoint as passed if provided
	if update.PassedWaypointID != nil {
		timestamp := time.Now().Unix()
		if err := s.tripRepo.MarkWaypointPassed(*update.PassedWaypointID, timestamp); err != nil {
			return nil, err
		}
	}

	updatedTrip, err := s.tripRepo.GetByIDWithRelations(id)
	if err != nil {
		return nil, err
	}

	updatedTrip.Route.Waypoints = nil

	// Publish event to RabbitMQ
	if s.rabbitMQService != nil {
		_ = s.rabbitMQService.PublishTripEvent("updated", *updatedTrip)
	}

	// Broadcast SSE event
	if s.sseService != nil {
		s.sseService.BroadcastTripEventToSessions(models.TripEventMessage{
			Event: "updated",
			Data:  *updatedTrip,
		})
	}

	return updatedTrip, nil
}

func (s *TripService) StartTrip(id int64) (*models.Trip, error) {
	trip, err := s.tripRepo.GetByID(id)
	if err != nil {
		return nil, errors.New("trip not found")
	}

	if trip.Status != "SCHEDULED" {
		return nil, errors.New("trip must be scheduled to start")
	}

	updates := map[string]interface{}{
		"status":     "IN_PROGRESS",
		"updated_at": time.Now(),
	}

	if err := s.tripRepo.UpdateProgress(id, updates); err != nil {
		return nil, err
	}

	startedTrip, err := s.tripRepo.GetByIDWithRelations(id)
	if err != nil {
		return nil, err
	}

	startedTrip.Route.Waypoints = nil

	// Broadcast SSE event for trip start
	if s.sseService != nil {
		s.sseService.BroadcastTripEventToSessions(models.TripEventMessage{
			Event: "started",
			Data:  *startedTrip,
		})
	}

	return startedTrip, nil
}

func (s *TripService) CompleteTrip(id int64) (*models.Trip, error) {
	trip, err := s.tripRepo.GetByID(id)
	if err != nil {
		return nil, errors.New("trip not found")
	}

	if trip.Status != "IN_PROGRESS" {
		return nil, errors.New("trip must be in progress to complete")
	}

	completionTime := time.Now().Unix()
	updates := map[string]interface{}{
		"status":          "COMPLETED",
		"completion_time": completionTime,
		"updated_at":      time.Now(),
	}

	if err := s.tripRepo.UpdateProgress(id, updates); err != nil {
		return nil, err
	}

	completedTrip, err := s.tripRepo.GetByIDWithRelations(id)
	if err != nil {
		return nil, err
	}

	completedTrip.Route.Waypoints = nil

	// Broadcast SSE event for trip completion
	if s.sseService != nil {
		s.sseService.BroadcastTripEventToSessions(models.TripEventMessage{
			Event: "completed",
			Data:  *completedTrip,
		})
	}

	return completedTrip, nil
}

func (s *TripService) GetTripByID(id int64) (*models.Trip, error) {
	trip, err := s.tripRepo.GetByIDWithRelations(id)
	if err != nil {
		return nil, err
	}

	trip.Route.Waypoints = nil

	return trip, nil
}

func (s *TripService) GetAllTrips() ([]models.Trip, error) {
	trips, err := s.tripRepo.GetAll()
	if err != nil {
		return nil, err
	}

	// Clear route waypoints for all trips
	for i := range trips {

		trips[i].Route.Waypoints = nil

	}

	return trips, nil
}

func (s *TripService) GetTripsByStatus(status string) ([]models.Trip, error) {
	trips, err := s.tripRepo.GetTripsByStatus(status)
	if err != nil {
		return nil, err
	}

	// Clear route waypoints for all trips
	for i := range trips {

		trips[i].Route.Waypoints = nil
	}

	return trips, nil
}

func (s *TripService) GetTripsByCarPlate(carPlate string) ([]models.Trip, error) {
	trips, err := s.tripRepo.GetTripsByCarPlate(carPlate)
	if err != nil {
		return nil, err
	}

	// Clear route waypoints for all trips
	for i := range trips {

		trips[i].Route.Waypoints = nil

	}

	return trips, nil
}

func (s *TripService) GetTripProgress(id int64) (*models.Trip, error) {
	trip, err := s.tripRepo.GetByIDWithRelations(id)
	if err != nil {
		return nil, errors.New("trip not found")
	}

	trip.Route.Waypoints = nil

	return trip, nil
}

func (s *TripService) GetTripsByFilters(origin, destination, company string) ([]models.Trip, error) {
	return s.tripRepo.GetTripsByFilters(origin, destination, company)
}

func (s *TripService) GetTripsByFiltersPaginated(origin, destination, company string, limit, offset int) ([]models.Trip, int64, error) {
	return s.tripRepo.GetTripsByFiltersPaginated(origin, destination, company, limit, offset)
}

func (s *TripService) GetTripsByVehicleID(vehicleID int64) ([]models.Trip, error) {
	trips, err := s.tripRepo.GetTripsByVehicleID(vehicleID)
	if err != nil {
		return nil, err
	}
	for i := range trips {
		trips[i].Route.Waypoints = nil
	}
	return trips, nil
}

func (s *TripService) GetTripsByCityRoute(cityRoute bool) ([]models.Trip, error) {
	trips, err := s.tripRepo.GetTripsByCityRoute(cityRoute)
	if err != nil {
		return nil, err
	}
	// Clear route waypoints for all trips
	for i := range trips {
		trips[i].Route.Waypoints = nil
	}
	return trips, nil
}

// UpdateTripFields updates arbitrary fields for a trip by id
func (s *TripService) UpdateTripFields(id int64, updates map[string]interface{}) error {
	return s.tripRepo.UpdateProgress(id, updates)
}

// GetTripsByFiltersWithCityRoute fetches trips filtered by origin, destination, company, and cityRoute (if provided)
func (s *TripService) GetTripsByFiltersWithCityRoute(origin, destination, company string, cityRoute *bool, limit, offset int) ([]models.Trip, int64, error) {
	if cityRoute == nil {
		return s.GetTripsByFiltersPaginated(origin, destination, company, limit, offset)
	}
	return s.tripRepo.GetTripsByFiltersWithCityRoute(origin, destination, company, *cityRoute, limit, offset)
}
