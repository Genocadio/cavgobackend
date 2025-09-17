package service

import (
	"cavgotrips/internal/models"
	"cavgotrips/internal/repository"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"sort"
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

	// Determine trip price: use provided price or route price
	var tripPrice *float64
	if request.Price != nil {
		tripPrice = request.Price
	} else {
		tripPrice = &route.RoutePrice
	}

	// Create the trip
	trip := &models.Trip{
		RouteID:            request.RouteID,
		VehicleID:          request.VehicleID,
		Vehicle:            vehicle,
		Status:             "SCHEDULED",
		DepartureTime:      request.DepartureTime,
		ConnectionMode:     request.ConnectionMode,
		Price:              tripPrice,
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
		// Include only passthrough waypoints from the route
		waypoints := route.Waypoints
		if request.IsReversed {
			for i := len(waypoints) - 1; i >= 0; i-- {
				rw := waypoints[i]
				if !rw.IsPassThrough {
					continue
				}
				tw := &models.TripWaypoint{
					TripID:        trip.ID,
					LocationID:    rw.LocationID,
					Order:         len(waypoints) - i,
					Price:         nil,
					IsCustom:      false,
					IsPassThrough: true,
				}
				if err := s.tripRepo.CreateWaypoint(tw); err != nil {
					return nil, err
				}
			}
		} else {
			for _, rw := range waypoints {
				if !rw.IsPassThrough {
					continue
				}
				tw := &models.TripWaypoint{
					TripID:        trip.ID,
					LocationID:    rw.LocationID,
					Order:         rw.Order,
					Price:         nil,
					IsCustom:      false,
					IsPassThrough: true,
				}
				if err := s.tripRepo.CreateWaypoint(tw); err != nil {
					return nil, err
				}
			}
		}
	} else if len(request.CustomWaypoints) > 0 {
		// Use custom waypoints but ensure route passthroughs are included
		customByLocation := make(map[int64]models.CreateCustomWaypoint)
		for _, cw := range request.CustomWaypoints {
			customByLocation[cw.LocationID] = cw
		}
		var tripWaypoints []models.TripWaypoint
		for _, customWaypoint := range request.CustomWaypoints {
			if err := s.locationRepo.ValidateExists(customWaypoint.LocationID); err != nil {
				return nil, models.NewValidationError("invalid location in custom waypoints " + strconv.FormatInt(customWaypoint.LocationID, 10))
			}
			isPass := false
			for _, rw := range route.Waypoints {
				if rw.IsPassThrough && rw.LocationID == customWaypoint.LocationID {
					isPass = true
					break
				}
			}
			tripWaypoints = append(tripWaypoints, models.TripWaypoint{
				TripID:            trip.ID,
				LocationID:        customWaypoint.LocationID,
				Order:             customWaypoint.Order,
				Price:             customWaypoint.Price,
				RemainingTime:     customWaypoint.RemainingTime,
				RemainingDistance: customWaypoint.RemainingDistance,
				IsCustom:          true,
				IsPassThrough:     isPass,
			})
		}
		for _, rw := range route.Waypoints {
			if !rw.IsPassThrough {
				continue
			}
			if _, ok := customByLocation[rw.LocationID]; ok {
				continue
			}
			tripWaypoints = append(tripWaypoints, models.TripWaypoint{
				TripID:        trip.ID,
				LocationID:    rw.LocationID,
				Order:         rw.Order,
				Price:         nil,
				IsCustom:      false,
				IsPassThrough: true,
			})
		}
		sort.SliceStable(tripWaypoints, func(i, j int) bool { return tripWaypoints[i].Order < tripWaypoints[j].Order })
		for i := range tripWaypoints {
			tripWaypoints[i].Order = i + 1
			wp := tripWaypoints[i]
			if err := s.tripRepo.CreateWaypoint(&wp); err != nil {
				return nil, err
			}
		}
	} else {
		// Use route waypoints
		waypoints := route.Waypoints
		if request.IsReversed {
			// Reverse the waypoints and recalculate prices based on total trip price
			// Original waypoint prices are cumulative from origin -> waypoint.
			// For reversed trips, price at each waypoint should be from destination -> waypoint,
			// i.e., (total trip price) - (original cumulative price).
			totalPrice := *tripPrice
			for i := len(waypoints) - 1; i >= 0; i-- {
				routeWaypoint := waypoints[i]
				var pricePtr *float64
				if routeWaypoint.IsPassThrough || routeWaypoint.Price == nil {
					pricePtr = nil
				} else {
					calculated := totalPrice - *routeWaypoint.Price
					if calculated <= 0 {
						min := 0.01
						pricePtr = &min
					} else {
						cp := calculated
						pricePtr = &cp
					}
				}
				tripWaypoint := &models.TripWaypoint{
					TripID:        trip.ID,
					LocationID:    routeWaypoint.LocationID,
					Order:         len(waypoints) - i,
					Price:         pricePtr,
					IsCustom:      false,
					IsPassThrough: routeWaypoint.IsPassThrough,
				}

				if err := s.tripRepo.CreateWaypoint(tripWaypoint); err != nil {
					return nil, err
				}
			}
		} else {
			// Normal order
			for _, routeWaypoint := range waypoints {
				tripWaypoint := &models.TripWaypoint{
					TripID:        trip.ID,
					LocationID:    routeWaypoint.LocationID,
					Order:         routeWaypoint.Order,
					Price:         routeWaypoint.Price,
					IsCustom:      false,
					IsPassThrough: routeWaypoint.IsPassThrough,
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
	// If reversed, swap route origin/destination for response consistency
	adjustRouteForReversed(createdTrip)

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
	// If reversed, swap route origin/destination for response consistency
	adjustRouteForReversed(updatedTrip)

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
	// If reversed, swap route origin/destination for response consistency
	adjustRouteForReversed(startedTrip)

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
	// If reversed, swap route origin/destination for response consistency
	adjustRouteForReversed(completedTrip)

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
	// If reversed, swap route origin/destination for response consistency
	adjustRouteForReversed(trip)

	return trip, nil
}

func (s *TripService) GetAllTrips() ([]models.Trip, error) {
	trips, err := s.tripRepo.GetAll()
	if err != nil {
		return nil, err
	}

	// Clear route waypoints and adjust origin/destination for all trips
	for i := range trips {
		trips[i].Route.Waypoints = nil
		adjustRouteForReversed(&trips[i])
	}

	return trips, nil
}

func (s *TripService) GetTripsByStatus(status string) ([]models.Trip, error) {
	trips, err := s.tripRepo.GetTripsByStatus(status)
	if err != nil {
		return nil, err
	}

	// Clear route waypoints and adjust origin/destination for all trips
	for i := range trips {
		trips[i].Route.Waypoints = nil
		adjustRouteForReversed(&trips[i])
	}

	return trips, nil
}

func (s *TripService) GetTripsByCarPlate(carPlate string) ([]models.Trip, error) {
	trips, err := s.tripRepo.GetTripsByCarPlate(carPlate)
	if err != nil {
		return nil, err
	}

	// Clear route waypoints and adjust origin/destination for all trips
	for i := range trips {
		trips[i].Route.Waypoints = nil
		adjustRouteForReversed(&trips[i])
	}

	return trips, nil
}

func (s *TripService) GetTripProgress(id int64) (*models.Trip, error) {
	trip, err := s.tripRepo.GetByIDWithRelations(id)
	if err != nil {
		return nil, errors.New("trip not found")
	}

	trip.Route.Waypoints = nil
	// If reversed, swap route origin/destination for response consistency
	adjustRouteForReversed(trip)

	return trip, nil
}

func (s *TripService) GetTripsByFilters(origin, destination, company string) ([]models.Trip, error) {
	trips, err := s.tripRepo.GetTripsByFilters(origin, destination, company)
	if err != nil {
		return nil, err
	}
	for i := range trips {
		trips[i].Route.Waypoints = nil
		adjustRouteForReversed(&trips[i])
	}
	return trips, nil
}

func (s *TripService) GetTripsByFiltersPaginated(origin, destination, company string, limit, offset int) ([]models.Trip, int64, error) {
	trips, total, err := s.tripRepo.GetTripsByFiltersPaginated(origin, destination, company, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	for i := range trips {
		trips[i].Route.Waypoints = nil
		adjustRouteForReversed(&trips[i])
	}
	return trips, total, nil
}

func (s *TripService) GetTripsByVehicleID(vehicleID int64) ([]models.Trip, error) {
	trips, err := s.tripRepo.GetTripsByVehicleID(vehicleID)
	if err != nil {
		return nil, err
	}
	for i := range trips {
		trips[i].Route.Waypoints = nil
		adjustRouteForReversed(&trips[i])
	}
	return trips, nil
}

func (s *TripService) GetTripsByCityRoute(cityRoute bool) ([]models.Trip, error) {
	trips, err := s.tripRepo.GetTripsByCityRoute(cityRoute)
	if err != nil {
		return nil, err
	}
	// Clear route waypoints and adjust origin/destination for all trips
	for i := range trips {
		trips[i].Route.Waypoints = nil
		adjustRouteForReversed(&trips[i])
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
	trips, total, err := s.tripRepo.GetTripsByFiltersWithCityRoute(origin, destination, company, *cityRoute, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	for i := range trips {
		trips[i].Route.Waypoints = nil
		adjustRouteForReversed(&trips[i])
	}
	return trips, total, nil
}

func (s *TripService) DeleteTrip(id int64) error {
	// Get the trip to check if it exists and get its status
	trip, err := s.tripRepo.GetByID(id)
	if err != nil {
		return errors.New("trip not found")
	}

	// Only allow deletion of SCHEDULED trips (not IN_PROGRESS or COMPLETED)
	if trip.Status != "SCHEDULED" {
		return errors.New("can only delete scheduled trips")
	}

	// Delete the trip (this will also delete associated waypoints)
	if err := s.tripRepo.Delete(id); err != nil {
		return err
	}

	// Broadcast SSE event for trip deletion
	if s.sseService != nil {
		s.sseService.BroadcastTripEventToSessions(models.TripEventMessage{
			Event: "deleted",
			Data:  *trip,
		})
	}

	return nil
}

// UpdateTripFromMQTT updates a trip with data from MQTT service without publishing back to RabbitMQ
func (s *TripService) UpdateTripFromMQTT(mqttTrip models.Trip) (*models.Trip, error) {
	// Get the existing trip to ensure it exists
	_, err := s.tripRepo.GetByIDWithRelations(mqttTrip.ID)
	if err != nil {
		return nil, fmt.Errorf("trip not found: %w", err)
	}

	// Prepare updates map with only the fields that should be updated from MQTT
	updates := make(map[string]interface{})

	// Update status if provided
	if mqttTrip.Status != "" {
		updates["status"] = mqttTrip.Status
	}

	// Update completion time if provided
	if mqttTrip.CompletionTime != nil {
		updates["completion_time"] = *mqttTrip.CompletionTime
	}

	// Update connection mode if provided
	if mqttTrip.ConnectionMode != "" {
		updates["connection_mode"] = mqttTrip.ConnectionMode
	}

	// Update notes if provided
	if mqttTrip.Notes != nil {
		updates["notes"] = *mqttTrip.Notes
	}

	// Update remaining time to destination if provided
	if mqttTrip.RemainingTimeToDestination != nil {
		updates["remaining_time_to_destination"] = *mqttTrip.RemainingTimeToDestination
	}

	// Update remaining distance to destination if provided
	if mqttTrip.RemainingDistanceToDestination != nil {
		updates["remaining_distance_to_destination"] = *mqttTrip.RemainingDistanceToDestination
	}

	// Update is reversed if provided
	updates["is_reversed"] = mqttTrip.IsReversed

	// Update current speed if provided
	if mqttTrip.CurrentSpeed != nil {
		updates["current_speed"] = *mqttTrip.CurrentSpeed
	}

	// Update current location if provided
	if mqttTrip.CurrentLatitude != nil {
		updates["current_latitude"] = *mqttTrip.CurrentLatitude
	}
	if mqttTrip.CurrentLongitude != nil {
		updates["current_longitude"] = *mqttTrip.CurrentLongitude
	}

	// Update has custom waypoints if provided
	updates["has_custom_waypoints"] = mqttTrip.HasCustomWaypoints

	// Always update the updated_at timestamp
	updates["updated_at"] = time.Now()

	// Update the trip in the database
	if err := s.tripRepo.UpdateProgress(mqttTrip.ID, updates); err != nil {
		return nil, fmt.Errorf("failed to update trip: %w", err)
	}

	// Update waypoints if provided in MQTT data
	if len(mqttTrip.Waypoints) > 0 {
		log.Printf("[UpdateTripFromMQTT] Processing %d waypoint updates for trip %d", len(mqttTrip.Waypoints), mqttTrip.ID)
		for _, waypointUpdate := range mqttTrip.Waypoints {
			waypointUpdates := make(map[string]interface{})
			
			// Update remaining time if provided
			if waypointUpdate.RemainingTime != nil {
				waypointUpdates["remaining_time"] = *waypointUpdate.RemainingTime
				log.Printf("[UpdateTripFromMQTT] Updating waypoint %d remaining_time: %d", waypointUpdate.ID, *waypointUpdate.RemainingTime)
			}
			
			// Update remaining distance if provided
			if waypointUpdate.RemainingDistance != nil {
				waypointUpdates["remaining_distance"] = *waypointUpdate.RemainingDistance
				log.Printf("[UpdateTripFromMQTT] Updating waypoint %d remaining_distance: %f", waypointUpdate.ID, *waypointUpdate.RemainingDistance)
			}
			
			// Update is_next flag
			waypointUpdates["is_next"] = waypointUpdate.IsNext
			log.Printf("[UpdateTripFromMQTT] Updating waypoint %d is_next: %t", waypointUpdate.ID, waypointUpdate.IsNext)
			
			// Update is_passed flag if provided
			waypointUpdates["is_passed"] = waypointUpdate.IsPassed
			log.Printf("[UpdateTripFromMQTT] Updating waypoint %d is_passed: %t", waypointUpdate.ID, waypointUpdate.IsPassed)
			
			// Update passed_timestamp if waypoint was marked as passed
			if waypointUpdate.IsPassed && waypointUpdate.PassedTimestamp != nil {
				waypointUpdates["passed_timestamp"] = *waypointUpdate.PassedTimestamp
				log.Printf("[UpdateTripFromMQTT] Updating waypoint %d passed_timestamp: %d", waypointUpdate.ID, *waypointUpdate.PassedTimestamp)
			}
			
			// Always update the updated_at timestamp for waypoints
			waypointUpdates["updated_at"] = time.Now()

			if err := s.tripRepo.UpdateWaypointProgress(waypointUpdate.ID, waypointUpdates); err != nil {
				log.Printf("[UpdateTripFromMQTT] Failed to update waypoint %d: %v", waypointUpdate.ID, err)
				return nil, fmt.Errorf("failed to update waypoint %d: %w", waypointUpdate.ID, err)
			}
			log.Printf("[UpdateTripFromMQTT] Successfully updated waypoint %d", waypointUpdate.ID)
		}
	} else {
		log.Printf("[UpdateTripFromMQTT] No waypoint updates provided for trip %d", mqttTrip.ID)
	}

	// Get the updated trip with relations
	updatedTrip, err := s.tripRepo.GetByIDWithRelations(mqttTrip.ID)
	if err != nil {
		return nil, fmt.Errorf("failed to get updated trip: %w", err)
	}

	// Broadcast SSE event for real-time updates (but don't publish to RabbitMQ)
	if s.sseService != nil {
		s.sseService.BroadcastTripEventToSessions(models.TripEventMessage{
			Event: "updated",
			Data:  *updatedTrip,
		})
	}

	return updatedTrip, nil
}

// adjustRouteForReversed swaps the route origin and destination (and their IDs)
// in the provided trip if the trip is marked as reversed. This affects only
// the in-memory representation returned to clients and does not persist changes
// to the underlying route.
func adjustRouteForReversed(trip *models.Trip) {
	if trip == nil {
		return
	}
	if !trip.IsReversed {
		return
	}
	// Swap IDs
	trip.Route.OriginID, trip.Route.DestinationID = trip.Route.DestinationID, trip.Route.OriginID
	// Swap Locations
	trip.Route.Origin, trip.Route.Destination = trip.Route.Destination, trip.Route.Origin
}
