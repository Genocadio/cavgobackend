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
	"sync"
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
	tripLogService    *TripLogService  // Add TripLogService
	scheduler         *TripUpdateScheduler
	poster            *TripUpdatePoster
	batchQueues       map[int64][]models.Trip // Company ID -> queue of trips for batch updates
	batchQueueMu      sync.RWMutex
}

func NewTripService(tripRepo repository.TripRepository, routeRepo repository.RouteRepository, locationRepo repository.LocationRepository, vehicleServiceURL string, rabbitMQService *RabbitMQService, sseService *SSEService, sessionService *SessionService, tripLogService *TripLogService, scheduler *TripUpdateScheduler, poster *TripUpdatePoster) *TripService {
	service := &TripService{
		tripRepo:          tripRepo,
		routeRepo:         routeRepo,
		locationRepo:      locationRepo,
		vehicleServiceURL: vehicleServiceURL,
		rabbitMQService:   rabbitMQService,
		sseService:        sseService,
		SessionService:    sessionService,
		tripLogService:    tripLogService,
		scheduler:         scheduler,
		poster:            poster,
		batchQueues:       make(map[int64][]models.Trip),
	}
	
	// Start batch processor if poster is available
	if poster != nil {
		go service.processBatchQueues()
	}
	
	return service
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
			ID        int64  `json:"id"`
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

	driverID := int64(0)
	driverName := ""
	driverPhone := ""
	if vehicleResp.Driver != nil {
		driverID = vehicleResp.Driver.ID
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
			ID:    driverID,
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

	// Log trip creation
	if s.tripLogService != nil {
		tripLogID, _ := s.tripLogService.LogTripUpdate(createdTrip, "created")
		// Log waypoint creations, using the trip log ID we just created
		for _, wp := range createdTrip.Waypoints {
			_ = s.tripLogService.LogWaypointUpdate(&wp, createdTrip.ID, "created", tripLogID)
		}
	}

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

	// Post trip update immediately for creation
	if s.poster != nil && s.scheduler != nil {
		companyID := createdTrip.Vehicle.CompanyID
		if s.scheduler.IsTimerActive(companyID) {
			s.poster.PostTripUpdate(companyID, createdTrip)
		}
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

	// Automatically update waypoint progress for IN_PROGRESS trips
	if trip.Status == "IN_PROGRESS" {
		if err := s.updateWaypointProgress(id); err != nil {
			log.Printf("[UpdateTripProgress] Failed to update waypoint progress: %v", err)
			// Don't fail the entire update, just log the error
		}
	}

	updatedTrip, err := s.tripRepo.GetByIDWithRelations(id)
	if err != nil {
		return nil, err
	}

	updatedTrip.Route.Waypoints = nil
	// If reversed, swap route origin/destination for response consistency
	adjustRouteForReversed(updatedTrip)

	// Log trip update
	if s.tripLogService != nil {
		tripLogID, _ := s.tripLogService.LogTripUpdate(updatedTrip, "updated")
		// Log waypoint updates if any were provided
		if len(update.WaypointUpdates) > 0 {
			for _, wpUpdate := range update.WaypointUpdates {
				// Find the waypoint in the updated trip
				for _, wp := range updatedTrip.Waypoints {
					if wp.ID == wpUpdate.WaypointID {
						_ = s.tripLogService.LogWaypointUpdate(&wp, updatedTrip.ID, "updated", tripLogID)
						break
					}
				}
			}
		}
		// Log passed waypoint if provided
		if update.PassedWaypointID != nil {
			for _, wp := range updatedTrip.Waypoints {
				if wp.ID == *update.PassedWaypointID {
					_ = s.tripLogService.LogWaypointUpdate(&wp, updatedTrip.ID, "passed", tripLogID)
					break
				}
			}
		}
	}

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

	// Handle trip update posting
	if s.poster != nil && s.scheduler != nil {
		companyID := updatedTrip.Vehicle.CompanyID
		if s.scheduler.IsTimerActive(companyID) {
			// Check if this is a status change that requires immediate posting
			isStatusChange := update.Status != nil && 
				(*update.Status == "IN_PROGRESS" || *update.Status == "CANCELLED" || *update.Status == "COMPLETED")
			
			if isStatusChange {
				// Post immediately for status changes
				s.poster.PostTripUpdate(companyID, updatedTrip)
			} else {
				// Queue for batch update (1 minute interval)
				s.queueTripForBatch(companyID, *updatedTrip)
			}
		}
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

	// Automatically update waypoint progress when trip starts
	if err := s.updateWaypointProgress(id); err != nil {
		log.Printf("[StartTrip] Failed to update waypoint progress: %v", err)
		// Don't fail the entire operation, just log the error
	}

	startedTrip, err := s.tripRepo.GetByIDWithRelations(id)
	if err != nil {
		return nil, err
	}

	startedTrip.Route.Waypoints = nil
	// If reversed, swap route origin/destination for response consistency
	adjustRouteForReversed(startedTrip)

	// Log trip start
	if s.tripLogService != nil {
		_, _ = s.tripLogService.LogTripUpdate(startedTrip, "started")
	}

	// Publish event to RabbitMQ
	if s.rabbitMQService != nil {
		_ = s.rabbitMQService.PublishTripEvent("started", *startedTrip)
	}

	// Broadcast SSE event for trip start
	if s.sseService != nil {
		s.sseService.BroadcastTripEventToSessions(models.TripEventMessage{
			Event: "started",
			Data:  *startedTrip,
		})
	}

	// Post trip update immediately for start
	if s.poster != nil && s.scheduler != nil {
		companyID := startedTrip.Vehicle.CompanyID
		if s.scheduler.IsTimerActive(companyID) {
			s.poster.PostTripUpdate(companyID, startedTrip)
		}
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

	// Log trip completion
	if s.tripLogService != nil {
		_, _ = s.tripLogService.LogTripUpdate(completedTrip, "completed")
	}

	// Publish event to RabbitMQ
	if s.rabbitMQService != nil {
		_ = s.rabbitMQService.PublishTripEvent("completed", *completedTrip)
	}

	// Broadcast SSE event for trip completion
	if s.sseService != nil {
		s.sseService.BroadcastTripEventToSessions(models.TripEventMessage{
			Event: "completed",
			Data:  *completedTrip,
		})
	}

	// Post trip update immediately for completion
	if s.poster != nil && s.scheduler != nil {
		companyID := completedTrip.Vehicle.CompanyID
		if s.scheduler.IsTimerActive(companyID) {
			s.poster.PostTripUpdate(companyID, completedTrip)
		}
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

func (s *TripService) GetTripsByDriverID(driverID int64) ([]models.Trip, error) {
	trips, err := s.tripRepo.GetTripsByDriverID(driverID)
	if err != nil {
		return nil, err
	}
	for i := range trips {
		trips[i].Route.Waypoints = nil
		adjustRouteForReversed(&trips[i])
	}
	return trips, nil
}

func (s *TripService) GetTripsByCompanyID(companyID int64, driverID *int64, vehicleID *int64, fromDate *time.Time, afterTripID *int64, limit, offset int) ([]models.Trip, int64, error) {
	trips, total, err := s.tripRepo.GetTripsByCompanyID(companyID, driverID, vehicleID, fromDate, afterTripID, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	for i := range trips {
		trips[i].Route.Waypoints = nil
		adjustRouteForReversed(&trips[i])
	}
	return trips, total, nil
}

func (s *TripService) GetDriverMetrics(driverID int64) (*models.DriverMetrics, error) {
	return s.tripRepo.GetDriverMetrics(driverID)
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

	// Handle different trip statuses
	if trip.Status == "SCHEDULED" || trip.Status == "IN_PROGRESS" {
		// Cancel the trip instead of deleting it (first delete call)
		updates := map[string]interface{}{
			"status":     "CANCELLED",
			"updated_at": time.Now(),
		}

		if err := s.tripRepo.UpdateProgress(id, updates); err != nil {
			return err
		}

		// Get the updated trip for events
		updatedTrip, err := s.tripRepo.GetByIDWithRelations(id)
		if err != nil {
			return err
		}

		updatedTrip.Route.Waypoints = nil
		// If reversed, swap route origin/destination for response consistency
		adjustRouteForReversed(updatedTrip)

		// Log trip cancellation
		if s.tripLogService != nil {
			_, _ = s.tripLogService.LogTripUpdate(updatedTrip, "cancelled")
		}

		// Publish event to RabbitMQ
		if s.rabbitMQService != nil {
			_ = s.rabbitMQService.PublishTripEvent("TRIP_CANCELLED", *updatedTrip)
		}

		// Broadcast SSE event for trip cancellation
		if s.sseService != nil {
			s.sseService.BroadcastTripEventToSessions(models.TripEventMessage{
				Event: "cancelled",
				Data:  *updatedTrip,
			})
		}

		// Post trip update immediately for cancellation
		if s.poster != nil && s.scheduler != nil {
			companyID := updatedTrip.Vehicle.CompanyID
			if s.scheduler.IsTimerActive(companyID) {
				s.poster.PostTripUpdate(companyID, updatedTrip)
			}
		}

		return nil
	} else if trip.Status == "CANCELLED" {
		// Get trip with relations for logging before deletion
		tripForLog, err := s.tripRepo.GetByIDWithRelations(id)
		if err == nil && s.tripLogService != nil {
			_, _ = s.tripLogService.LogTripUpdate(tripForLog, "deleted")
		}

		// Allow deletion of CANCELLED trips (second delete call)
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
	} else {
		// Cannot delete COMPLETED or NOT_COMPLETED trips
		return errors.New("cannot delete completed or not-completed trips")
	}
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

	// Automatically update waypoint progress for IN_PROGRESS trips
	if mqttTrip.Status == "IN_PROGRESS" {
		if err := s.updateWaypointProgress(mqttTrip.ID); err != nil {
			log.Printf("[UpdateTripFromMQTT] Failed to update waypoint progress: %v", err)
			// Don't fail the entire update, just log the error
		}
	}

	// Get the updated trip with relations
	updatedTrip, err := s.tripRepo.GetByIDWithRelations(mqttTrip.ID)
	if err != nil {
		return nil, fmt.Errorf("failed to get updated trip: %w", err)
	}

	// Log trip update from MQTT
	if s.tripLogService != nil {
		tripLogID, _ := s.tripLogService.LogTripUpdate(updatedTrip, "updated")
		// Log waypoint updates if any were provided
		if len(mqttTrip.Waypoints) > 0 {
			for _, wpUpdate := range mqttTrip.Waypoints {
				// Find the waypoint in the updated trip
				for _, wp := range updatedTrip.Waypoints {
					if wp.ID == wpUpdate.ID {
						updateType := "updated"
						if wpUpdate.IsPassed {
							updateType = "passed"
						}
						_ = s.tripLogService.LogWaypointUpdate(&wp, updatedTrip.ID, updateType, tripLogID)
						break
					}
				}
			}
		}
	}

	// Broadcast SSE event for real-time updates (but don't publish to RabbitMQ)
	if s.sseService != nil {
		s.sseService.BroadcastTripEventToSessions(models.TripEventMessage{
			Event: "updated",
			Data:  *updatedTrip,
		})
	}

	// Handle trip update posting for MQTT updates
	if s.poster != nil && s.scheduler != nil {
		companyID := updatedTrip.Vehicle.CompanyID
		if s.scheduler.IsTimerActive(companyID) {
			// Check if this is a status change that requires immediate posting
			isStatusChange := mqttTrip.Status != "" && 
				(mqttTrip.Status == "IN_PROGRESS" || mqttTrip.Status == "CANCELLED" || mqttTrip.Status == "COMPLETED")
			
			if isStatusChange {
				// Post immediately for status changes
				s.poster.PostTripUpdate(companyID, updatedTrip)
			} else {
				// Queue for batch update (1 minute interval)
				s.queueTripForBatch(companyID, *updatedTrip)
			}
		}
	}

	return updatedTrip, nil
}

// updateWaypointProgress automatically manages waypoint progress based on order
// Marks the first non-passed waypoint as is_next and clears is_next for others
func (s *TripService) updateWaypointProgress(tripID int64) error {
	// Get trip with waypoints
	trip, err := s.tripRepo.GetByIDWithRelations(tripID)
	if err != nil {
		return fmt.Errorf("failed to get trip with waypoints: %w", err)
	}

	if len(trip.Waypoints) == 0 {
		return nil // No waypoints to update
	}

	// Sort waypoints by order
	waypoints := trip.Waypoints
	sort.Slice(waypoints, func(i, j int) bool {
		return waypoints[i].Order < waypoints[j].Order
	})

	// Find the first non-passed waypoint
	nextWaypointID := int64(0)
	for _, wp := range waypoints {
		if !wp.IsPassed {
			nextWaypointID = wp.ID
			break
		}
	}

	// Update all waypoints: clear is_next for all, then set is_next for the next one
	for _, wp := range waypoints {
		updates := map[string]interface{}{
			"is_next":    wp.ID == nextWaypointID,
			"updated_at": time.Now(),
		}

		if err := s.tripRepo.UpdateWaypointProgress(wp.ID, updates); err != nil {
			log.Printf("[updateWaypointProgress] Failed to update waypoint %d: %v", wp.ID, err)
			return fmt.Errorf("failed to update waypoint %d: %w", wp.ID, err)
		}
	}

	log.Printf("[updateWaypointProgress] Updated waypoint progress for trip %d, next waypoint: %d", tripID, nextWaypointID)
	return nil
}

// GetTripLogs retrieves all logs for a specific trip
func (s *TripService) GetTripLogs(tripID int64) ([]models.TripLog, error) {
	if s.tripLogService == nil {
		return nil, nil
	}
	return s.tripLogService.GetTripLogs(tripID)
}

// queueTripForBatch adds a trip to the batch queue for a company
func (s *TripService) queueTripForBatch(companyID int64, trip models.Trip) {
	s.batchQueueMu.Lock()
	defer s.batchQueueMu.Unlock()
	
	// Use trip ID as key to avoid duplicates - keep only the latest version
	// Find and replace if exists, otherwise append
	found := false
	for i, queuedTrip := range s.batchQueues[companyID] {
		if queuedTrip.ID == trip.ID {
			s.batchQueues[companyID][i] = trip
			found = true
			break
		}
	}
	if !found {
		s.batchQueues[companyID] = append(s.batchQueues[companyID], trip)
	}
}

// processBatchQueues processes batch queues every 1 minute
func (s *TripService) processBatchQueues() {
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()
	
	for range ticker.C {
		s.batchQueueMu.Lock()
		queues := make(map[int64][]models.Trip)
		for companyID, trips := range s.batchQueues {
			if len(trips) > 0 {
				queues[companyID] = trips
				// Clear the queue after copying
				s.batchQueues[companyID] = []models.Trip{}
			}
		}
		s.batchQueueMu.Unlock()
		
		// Process each company's queue
		for companyID, trips := range queues {
			if s.scheduler != nil && s.scheduler.IsTimerActive(companyID) && s.poster != nil {
				// Post batch updates
				s.poster.PostBatchUpdates(companyID, trips)
			}
		}
	}
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
