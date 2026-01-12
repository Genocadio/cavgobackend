package service

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"time"

	"cavgoBooking/models"
	"cavgoBooking/repository"

	"github.com/google/uuid"
)

type TripSnapshotService interface {
	// InitializeSnapshot creates the first snapshot for a trip
	InitializeSnapshot(ctx context.Context, trip *models.Trip) error

	// OnBookingCreated updates snapshot when a booking is created (pending payment)
	OnBookingCreated(ctx context.Context, tripID int, pickupLocationID, dropoffLocationID string, numberOfTickets int, totalAmount float64, trip *models.Trip) error

	// OnPaymentConfirmed updates snapshot when payment is confirmed
	OnPaymentConfirmed(ctx context.Context, tripID int, pickupLocationID, dropoffLocationID string, numberOfTickets int, totalAmount float64) error

	// OnBookingExpired updates snapshot when booking expires/is cancelled
	OnBookingExpired(ctx context.Context, tripID int, pickupLocationID, dropoffLocationID string, numberOfTickets int, totalAmount float64) error

	// GetSnapshot retrieves the current snapshot for a trip
	GetSnapshot(ctx context.Context, tripID int) (*models.TripSnapshot, error)

	// PublishCurrentSnapshot fetches and publishes the snapshot without changing it
	PublishCurrentSnapshot(ctx context.Context, tripID int) error

	// ValidateBookableLocation checks if a location is bookable based on trip status
	ValidateBookableLocation(trip *models.Trip, pickupLocationID string) error

	// CheckSeatAvailability validates if enough seats are available at pickup location
	CheckSeatAvailability(ctx context.Context, tripID int, pickupLocationID string, requestedSeats int) error
}

type tripSnapshotService struct {
	repo      repository.TripSnapshotRepository
	publisher SnapshotPublisher
}

type SnapshotPublisher interface {
	PublishTripSnapshot(snapshot *models.TripSnapshotPublish) error
}

func NewTripSnapshotService(repo repository.TripSnapshotRepository, publisher SnapshotPublisher) TripSnapshotService {
	return &tripSnapshotService{
		repo:      repo,
		publisher: publisher,
	}
}

// InitializeSnapshot creates the first snapshot for a trip
func (s *tripSnapshotService) InitializeSnapshot(ctx context.Context, trip *models.Trip) error {
	fmt.Printf("[TripSnapshotService] Initializing snapshot for tripId=%d\n", trip.ID)

	// Create locations array
	locations := s.buildLocationsFromTrip(trip)

	snapshot := &models.TripSnapshot{
		ID:         uuid.New().String(),
		TripID:     trip.ID,
		TripStatus: trip.Status,
		Capacity: models.SnapshotCapacity{
			TotalSeats:          trip.Seats,
			AvailableSeats:      trip.Seats,
			OccupiedSeats:       0,
			PendingPaymentSeats: 0,
			TotalAmountPaid:     0.0,
			TotalAmountPending:  0.0,
		},
		Locations: locations,
		Summary: models.SnapshotSummary{
			TotalTickets:      0,
			PaidTickets:       0,
			PendingPayments:   0,
			CompletedDropoffs: 0,
		},
	}

	err := s.repo.CreateSnapshot(ctx, snapshot)
	if err != nil {
		return fmt.Errorf("failed to create snapshot: %w", err)
	}

	// Publish to RabbitMQ
	s.publishSnapshot(snapshot, "INITIALIZED")

	return nil
}

// OnBookingCreated handles snapshot update when booking is created (pending payment)
func (s *tripSnapshotService) OnBookingCreated(ctx context.Context, tripID int, pickupLocationID, dropoffLocationID string, numberOfTickets int, totalAmount float64, trip *models.Trip) error {
	fmt.Printf("[TripSnapshotService] OnBookingCreated: tripId=%d pickup=%s dropoff=%s tickets=%d amount=%.2f\n",
		tripID, pickupLocationID, dropoffLocationID, numberOfTickets, totalAmount)

	// Start transaction
	tx, err := s.repo.BeginTransaction(ctx)
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer tx.Rollback()

	// Get snapshot with lock
	snapshot, err := s.repo.GetSnapshotByTripIDForUpdate(ctx, tx, tripID)
	if err != nil {
		return fmt.Errorf("failed to get snapshot: %w", err)
	}

	// If no snapshot exists, create it first
	if snapshot == nil {
		if err := s.InitializeSnapshot(ctx, trip); err != nil {
			return fmt.Errorf("failed to initialize snapshot: %w", err)
		}
		// Re-fetch with lock
		snapshot, err = s.repo.GetSnapshotByTripIDForUpdate(ctx, tx, tripID)
		if err != nil {
			return fmt.Errorf("failed to get snapshot after init: %w", err)
		}
	}

	// Log BEFORE booking update
	s.logSnapshotState(snapshot, "BEFORE BOOKING_CREATED")

	// Update trip status
	snapshot.TripStatus = trip.Status

	// Update capacity: pending payment seat hold
	snapshot.Capacity.PendingPaymentSeats += numberOfTickets
	snapshot.Capacity.AvailableSeats -= numberOfTickets
	snapshot.Capacity.TotalAmountPending += totalAmount

	// Update location seats
	for i := range snapshot.Locations {
		loc := &snapshot.Locations[i]
		if s.locationMatches(loc, pickupLocationID) {
			loc.Seats.PendingPayment += numberOfTickets
			loc.Seats.TotalAmountPending += totalAmount
		}
		if s.locationMatches(loc, dropoffLocationID) {
			loc.Seats.PendingPayment += numberOfTickets
			loc.Seats.TotalAmountPending += totalAmount
		}
	}

	// Update summary
	snapshot.Summary.TotalTickets += numberOfTickets
	snapshot.Summary.PendingPayments += numberOfTickets

	// Recalculate availableFromHere for all locations
	s.recalculateAvailableSeats(snapshot)

	// Update location statuses based on current trip state
	s.updateLocationStatuses(snapshot, trip)

	// Log AFTER booking update (before commit)
	s.logSnapshotState(snapshot, "AFTER BOOKING_CREATED (before commit)")

	// Save snapshot
	err = s.repo.UpdateSnapshotInTx(ctx, tx, snapshot)
	if err != nil {
		return fmt.Errorf("failed to update snapshot: %w", err)
	}

	// Commit transaction
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("failed to commit transaction: %w", err)
	}

	// Publish to RabbitMQ
	s.publishSnapshot(snapshot, "BOOKING_CREATED")

	return nil
}

// OnPaymentConfirmed handles snapshot update when payment is confirmed
func (s *tripSnapshotService) OnPaymentConfirmed(ctx context.Context, tripID int, pickupLocationID, dropoffLocationID string, numberOfTickets int, totalAmount float64) error {
	fmt.Printf("[TripSnapshotService] OnPaymentConfirmed: tripId=%d pickup=%s dropoff=%s tickets=%d amount=%.2f\n",
		tripID, pickupLocationID, dropoffLocationID, numberOfTickets, totalAmount)

	// Start transaction
	tx, err := s.repo.BeginTransaction(ctx)
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer tx.Rollback()

	// Get snapshot with lock
	snapshot, err := s.repo.GetSnapshotByTripIDForUpdate(ctx, tx, tripID)
	if err != nil {
		return fmt.Errorf("failed to get snapshot: %w", err)
	}

	if snapshot == nil {
		return fmt.Errorf("snapshot not found for trip %d", tripID)
	}

	// Log BEFORE payment confirmation
	s.logSnapshotState(snapshot, "BEFORE PAYMENT_CONFIRMED")

	// Update capacity: convert pending to occupied
	snapshot.Capacity.PendingPaymentSeats -= numberOfTickets
	snapshot.Capacity.OccupiedSeats += numberOfTickets
	snapshot.Capacity.TotalAmountPending -= totalAmount
	snapshot.Capacity.TotalAmountPaid += totalAmount

	// Update location seats
	for i := range snapshot.Locations {
		loc := &snapshot.Locations[i]
		if s.locationMatches(loc, pickupLocationID) {
			loc.Seats.PendingPayment -= numberOfTickets
			loc.Seats.Pickup += numberOfTickets
			loc.Seats.TotalAmountPending -= totalAmount
			loc.Seats.TotalAmountPaid += totalAmount
		}
		if s.locationMatches(loc, dropoffLocationID) {
			loc.Seats.PendingPayment -= numberOfTickets
			loc.Seats.Dropoff += numberOfTickets
			loc.Seats.TotalAmountPending -= totalAmount
			loc.Seats.TotalAmountPaid += totalAmount
		}
	}

	// Update summary
	snapshot.Summary.PaidTickets += numberOfTickets
	snapshot.Summary.PendingPayments -= numberOfTickets

	// Recalculate availableFromHere for all locations
	s.recalculateAvailableSeats(snapshot)

	// Log AFTER payment confirmation (before commit)
	s.logSnapshotState(snapshot, "AFTER PAYMENT_CONFIRMED (before commit)")

	// Save snapshot
	err = s.repo.UpdateSnapshotInTx(ctx, tx, snapshot)
	if err != nil {
		return fmt.Errorf("failed to update snapshot: %w", err)
	}

	// Commit transaction
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("failed to commit transaction: %w", err)
	}

	// Publish to RabbitMQ
	s.publishSnapshot(snapshot, "PAYMENT_CONFIRMED")

	return nil
}

// OnBookingExpired handles snapshot update when booking expires or is cancelled
func (s *tripSnapshotService) OnBookingExpired(ctx context.Context, tripID int, pickupLocationID, dropoffLocationID string, numberOfTickets int, totalAmount float64) error {
	fmt.Printf("[TripSnapshotService] OnBookingExpired: tripId=%d pickup=%s dropoff=%s tickets=%d amount=%.2f\n",
		tripID, pickupLocationID, dropoffLocationID, numberOfTickets, totalAmount)

	// Start transaction
	tx, err := s.repo.BeginTransaction(ctx)
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer tx.Rollback()

	// Get snapshot with lock
	snapshot, err := s.repo.GetSnapshotByTripIDForUpdate(ctx, tx, tripID)
	if err != nil {
		return fmt.Errorf("failed to get snapshot: %w", err)
	}

	if snapshot == nil {
		return fmt.Errorf("snapshot not found for trip %d", tripID)
	}

	// Log BEFORE booking expiration
	s.logSnapshotState(snapshot, "BEFORE BOOKING_EXPIRED")

	// Update capacity: release held seats
	snapshot.Capacity.PendingPaymentSeats -= numberOfTickets
	snapshot.Capacity.AvailableSeats += numberOfTickets
	snapshot.Capacity.TotalAmountPending -= totalAmount

	// Update location seats
	for i := range snapshot.Locations {
		loc := &snapshot.Locations[i]
		if s.locationMatches(loc, pickupLocationID) {
			loc.Seats.PendingPayment -= numberOfTickets
			loc.Seats.TotalAmountPending -= totalAmount
		}
		if s.locationMatches(loc, dropoffLocationID) {
			loc.Seats.PendingPayment -= numberOfTickets
			loc.Seats.TotalAmountPending -= totalAmount
		}
	}

	// Update summary
	snapshot.Summary.TotalTickets -= numberOfTickets
	snapshot.Summary.PendingPayments -= numberOfTickets

	// Recalculate availableFromHere for all locations
	s.recalculateAvailableSeats(snapshot)

	// Log AFTER booking expiration (before commit)
	s.logSnapshotState(snapshot, "AFTER BOOKING_EXPIRED (before commit)")

	// Save snapshot
	err = s.repo.UpdateSnapshotInTx(ctx, tx, snapshot)
	if err != nil {
		return fmt.Errorf("failed to update snapshot: %w", err)
	}

	// Commit transaction
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("failed to commit transaction: %w", err)
	}

	// Publish to RabbitMQ
	s.publishSnapshot(snapshot, "BOOKING_EXPIRED")

	return nil
}

// GetSnapshot retrieves the current snapshot
func (s *tripSnapshotService) GetSnapshot(ctx context.Context, tripID int) (*models.TripSnapshot, error) {
	return s.repo.GetSnapshotByTripID(ctx, tripID)
}

// PublishCurrentSnapshot fetches the current snapshot and publishes it
func (s *tripSnapshotService) PublishCurrentSnapshot(ctx context.Context, tripID int) error {
	snapshot, err := s.repo.GetSnapshotByTripID(ctx, tripID)
	if err != nil {
		return fmt.Errorf("failed to get snapshot: %w", err)
	}
	if snapshot == nil {
		return fmt.Errorf("no snapshot found for trip %d", tripID)
	}
	publishSnapshot := &models.TripSnapshotPublish{
		TripID:      strconv.Itoa(snapshot.TripID),
		TripStatus:  snapshot.TripStatus,
		LastUpdated: snapshot.LastUpdated.Format(time.RFC3339),
		Capacity:    snapshot.Capacity,
		Locations:   snapshot.Locations,
		Summary:     snapshot.Summary,
	}
	if s.publisher != nil {
		if err := s.publisher.PublishTripSnapshot(publishSnapshot); err != nil {
			return fmt.Errorf("failed to publish snapshot: %w", err)
		}
	}
	return nil
}

// ValidateBookableLocation checks if a pickup location is bookable based on trip status
func (s *tripSnapshotService) ValidateBookableLocation(trip *models.Trip, pickupLocationID string) error {
	pickupLocID, err := strconv.Atoi(pickupLocationID)
	if err != nil {
		return fmt.Errorf("invalid pickup location ID: %s", pickupLocationID)
	}

	// SCHEDULED: For city trips, allow booking from all unpassed origins; for non-city trips, only origin is bookable
	if trip.Status == "SCHEDULED" {
		if trip.Route.CityRoute {
			// City trip: Check if location is available (origin or unpassed waypoints)
			if pickupLocID == trip.Route.OriginID {
				return nil
			}

			// Check waypoints - must not be passed
			for _, waypoint := range trip.Waypoints {
				if waypoint.LocationID == pickupLocID {
					if waypoint.IsPassed {
						return fmt.Errorf("location %s has already been passed and is not bookable", pickupLocationID)
					}
					// Location is a waypoint and not passed - bookable
					return nil
				}
			}

			return fmt.Errorf("location %s is not found in trip route", pickupLocationID)
		} else {
			// Non-city trip: only origin is bookable
			if pickupLocID == trip.Route.OriginID {
				return nil
			}
			return fmt.Errorf("trip is SCHEDULED, only origin (ID: %d) is bookable as pickup", trip.Route.OriginID)
		}
	}

	// IN_PROGRESS: origin is not bookable, only unpassed waypoints
	if trip.Status == "IN_PROGRESS" {
		// Check if trying to book from origin (not allowed)
		if pickupLocID == trip.Route.OriginID {
			return fmt.Errorf("trip is IN_PROGRESS, origin has been passed and is no longer bookable")
		}

		// Check waypoints - must not be passed
		for _, waypoint := range trip.Waypoints {
			if waypoint.LocationID == pickupLocID {
				if waypoint.IsPassed {
					return fmt.Errorf("location %s has already been passed and is not bookable", pickupLocationID)
				}
				// Location is a waypoint and not passed - bookable
				return nil
			}
		}

		// Check if it's the destination (always bookable if trip not completed)
		if pickupLocID == trip.Route.DestinationID {
			return fmt.Errorf("destination cannot be used as pickup location")
		}

		return fmt.Errorf("location %s is not found in trip waypoints", pickupLocationID)
	}

	return fmt.Errorf("trip status %s does not allow bookings", trip.Status)
}

// CheckSeatAvailability validates if enough seats are available
func (s *tripSnapshotService) CheckSeatAvailability(ctx context.Context, tripID int, pickupLocationID string, requestedSeats int) error {
	snapshot, err := s.repo.GetSnapshotByTripID(ctx, tripID)
	if err != nil {
		return fmt.Errorf("failed to get snapshot: %w", err)
	}

	// If no snapshot, check against total seats only (first booking)
	if snapshot == nil {
		return nil // Will be validated in booking service against trip.Seats
	}

	// Check overall availability
	if snapshot.Capacity.AvailableSeats < requestedSeats {
		return fmt.Errorf("not enough available seats: requested=%d, available=%d",
			requestedSeats, snapshot.Capacity.AvailableSeats)
	}

	// Check availability from pickup location
	for _, loc := range snapshot.Locations {
		if s.locationMatches(&loc, pickupLocationID) {
			if loc.Seats.AvailableFromHere < requestedSeats {
				return fmt.Errorf("not enough seats available from location %s: requested=%d, available=%d",
					pickupLocationID, requestedSeats, loc.Seats.AvailableFromHere)
			}
			return nil
		}
	}

	return nil
}

// buildLocationsFromTrip creates the locations array from trip data
func (s *tripSnapshotService) buildLocationsFromTrip(trip *models.Trip) []models.SnapshotLocation {
	locations := []models.SnapshotLocation{}

	// Add origin
	originStatus := s.determineLocationStatus(trip, trip.Route.OriginID, true, false)
	locations = append(locations, models.SnapshotLocation{
		LocationID:      strconv.Itoa(trip.Route.OriginID),
		RouteLocationID: trip.Route.OriginID,
		Type:            models.LocationTypeOrigin,
		Order:           0,
		Status:          originStatus,
		Seats: models.LocationSeats{
			Pickup:             0,
			Dropoff:            0,
			PendingPayment:     0,
			AvailableFromHere:  trip.Seats,
			TotalAmountPaid:    0.0,
			TotalAmountPending: 0.0,
		},
	})

	// Add waypoints
	for _, waypoint := range trip.Waypoints {
		status := s.determineLocationStatus(trip, waypoint.LocationID, false, false)
		locations = append(locations, models.SnapshotLocation{
			LocationID:      strconv.Itoa(waypoint.ID),
			RouteLocationID: waypoint.LocationID,
			Type:            models.LocationTypeWaypoint,
			Order:           waypoint.Order,
			Status:          status,
			Seats: models.LocationSeats{
				Pickup:             0,
				Dropoff:            0,
				PendingPayment:     0,
				AvailableFromHere:  trip.Seats,
				TotalAmountPaid:    0.0,
				TotalAmountPending: 0.0,
			},
		})
	}

	// Add destination
	destStatus := s.determineLocationStatus(trip, trip.Route.DestinationID, false, true)
	locations = append(locations, models.SnapshotLocation{
		LocationID:      strconv.Itoa(trip.Route.DestinationID),
		RouteLocationID: trip.Route.DestinationID,
		Type:            models.LocationTypeDestination,
		Order:           len(trip.Waypoints) + 1,
		Status:          destStatus,
		Seats: models.LocationSeats{
			Pickup:             0,
			Dropoff:            0,
			PendingPayment:     0,
			AvailableFromHere:  0, // Can't pick up from destination
			TotalAmountPaid:    0.0,
			TotalAmountPending: 0.0,
		},
	})

	return locations
}

// determineLocationStatus determines the status of a location based on trip state
func (s *tripSnapshotService) determineLocationStatus(trip *models.Trip, locationID int, isOrigin, isDestination bool) models.LocationStatus {
	if trip.Status == "SCHEDULED" {
		if isOrigin {
			return models.LocationStatusCurrent
		}
		return models.LocationStatusUpcoming
	}

	if trip.Status == "IN_PROGRESS" {
		if isOrigin {
			return models.LocationStatusPassed
		}

		// Check waypoints
		for _, waypoint := range trip.Waypoints {
			if waypoint.LocationID == locationID {
				if waypoint.IsPassed {
					return models.LocationStatusPassed
				}
				if waypoint.IsNext {
					return models.LocationStatusCurrent
				}
				return models.LocationStatusUpcoming
			}
		}

		if isDestination {
			return models.LocationStatusUpcoming
		}
	}

	return models.LocationStatusUpcoming
}

// updateLocationStatuses updates all location statuses based on current trip state
func (s *tripSnapshotService) updateLocationStatuses(snapshot *models.TripSnapshot, trip *models.Trip) {
	for i := range snapshot.Locations {
		loc := &snapshot.Locations[i]

		switch loc.Type {
		case models.LocationTypeOrigin:
			loc.RouteLocationID = trip.Route.OriginID
			loc.Status = s.determineLocationStatus(trip, trip.Route.OriginID, true, false)

		case models.LocationTypeDestination:
			loc.RouteLocationID = trip.Route.DestinationID
			loc.Status = s.determineLocationStatus(trip, trip.Route.DestinationID, false, true)

		case models.LocationTypeWaypoint:
			waypoint := s.findWaypoint(trip.Waypoints, loc.LocationID, loc.RouteLocationID)
			if waypoint != nil {
				loc.RouteLocationID = waypoint.LocationID
				loc.Status = s.determineLocationStatus(trip, waypoint.LocationID, false, false)
			} else {
				loc.Status = models.LocationStatusUpcoming
			}

		default:
			loc.Status = models.LocationStatusUpcoming
		}
	}
}

// recalculateAvailableSeats calculates availableFromHere for each location
func (s *tripSnapshotService) recalculateAvailableSeats(snapshot *models.TripSnapshot) {
	totalSeats := snapshot.Capacity.TotalSeats

	// Calculate cumulative passengers at each location
	cumulativePickup := 0
	cumulativeDropoff := 0

	for i := range snapshot.Locations {
		loc := &snapshot.Locations[i]

		// Add pickups at this location
		cumulativePickup += loc.Seats.Pickup

		// Subtract dropoffs at this location
		cumulativeDropoff += loc.Seats.Dropoff

		// Current passengers = pickups - dropoffs
		currentPassengers := cumulativePickup - cumulativeDropoff

		// Available from here = total - current - pending
		loc.Seats.AvailableFromHere = totalSeats - currentPassengers - snapshot.Capacity.PendingPaymentSeats

		// Ensure non-negative
		if loc.Seats.AvailableFromHere < 0 {
			loc.Seats.AvailableFromHere = 0
		}

		// Destination can't be pickup location
		if loc.Type == models.LocationTypeDestination {
			loc.Seats.AvailableFromHere = 0
		}
	}
}

// locationMatches checks if the provided id matches either the snapshot location identifier or its underlying route location id.
func (s *tripSnapshotService) locationMatches(loc *models.SnapshotLocation, targetID string) bool {
	if loc.LocationID == targetID {
		return true
	}

	if loc.RouteLocationID != 0 && strconv.Itoa(loc.RouteLocationID) == targetID {
		return true
	}

	return false
}

// findWaypoint locates a waypoint using either the waypoint ID (preferred) or the route location id.
func (s *tripSnapshotService) findWaypoint(waypoints []models.TripWaypoint, waypointID string, routeLocationID int) *models.TripWaypoint {
	// Prefer direct waypoint ID match
	if id, err := strconv.Atoi(waypointID); err == nil {
		for i := range waypoints {
			if waypoints[i].ID == id {
				return &waypoints[i]
			}
		}
	}

	// Fallback to route location id
	if routeLocationID != 0 {
		for i := range waypoints {
			if waypoints[i].LocationID == routeLocationID {
				return &waypoints[i]
			}
		}
	}

	return nil
}

// publishSnapshot publishes the snapshot to RabbitMQ and logs to console
func (s *tripSnapshotService) publishSnapshot(snapshot *models.TripSnapshot, eventType string) {
	// Convert to publish format with ISO timestamp
	publishSnapshot := &models.TripSnapshotPublish{
		TripID:      strconv.Itoa(snapshot.TripID),
		TripStatus:  snapshot.TripStatus,
		LastUpdated: snapshot.LastUpdated.Format(time.RFC3339),
		Capacity:    snapshot.Capacity,
		Locations:   snapshot.Locations,
		Summary:     snapshot.Summary,
	}

	// Log to console
	snapshotJSON, _ := json.MarshalIndent(publishSnapshot, "", "  ")
	fmt.Printf("\n========== TRIP SNAPSHOT [%s] ==========\n", eventType)
	fmt.Printf("TripID: %s | Status: %s | LastUpdated: %s\n",
		publishSnapshot.TripID, publishSnapshot.TripStatus, publishSnapshot.LastUpdated)
	fmt.Printf("\nCapacity:\n")
	fmt.Printf("  Total Seats:           %d\n", snapshot.Capacity.TotalSeats)
	fmt.Printf("  Available Seats:       %d\n", snapshot.Capacity.AvailableSeats)
	fmt.Printf("  Occupied Seats:        %d\n", snapshot.Capacity.OccupiedSeats)
	fmt.Printf("  Pending Payment Seats: %d\n", snapshot.Capacity.PendingPaymentSeats)
	fmt.Printf("\nLocations:\n")
	for _, loc := range snapshot.Locations {
		fmt.Printf("  [%s] %s (Order: %d, Status: %s)\n", loc.Type, loc.LocationID, loc.Order, loc.Status)
		fmt.Printf("    Pickup: %d | Dropoff: %d | Pending: %d | AvailableFromHere: %d\n",
			loc.Seats.Pickup, loc.Seats.Dropoff, loc.Seats.PendingPayment, loc.Seats.AvailableFromHere)
	}
	fmt.Printf("\nSummary:\n")
	fmt.Printf("  Total Tickets:       %d\n", snapshot.Summary.TotalTickets)
	fmt.Printf("  Paid Tickets:        %d\n", snapshot.Summary.PaidTickets)
	fmt.Printf("  Pending Payments:    %d\n", snapshot.Summary.PendingPayments)
	fmt.Printf("  Completed Dropoffs:  %d\n", snapshot.Summary.CompletedDropoffs)
	fmt.Printf("\nFull JSON:\n%s\n", string(snapshotJSON))
	fmt.Printf("==========================================\n\n")

	// Publish to RabbitMQ
	if s.publisher != nil {
		if err := s.publisher.PublishTripSnapshot(publishSnapshot); err != nil {
			fmt.Printf("[TripSnapshotService] ERROR: Failed to publish snapshot: %v\n", err)
		} else {
			fmt.Printf("[TripSnapshotService] ✓ Successfully published snapshot [%s] to RabbitMQ for TripID=%s\n", eventType, publishSnapshot.TripID)
		}
	} else {
		fmt.Printf("[TripSnapshotService] ⚠ Publisher is nil, snapshot not published to RabbitMQ\n")
	}
}

// logSnapshotState logs the current snapshot state for debugging
func (s *tripSnapshotService) logSnapshotState(snapshot *models.TripSnapshot, stage string) {
	fmt.Printf("\n========== SNAPSHOT STATE [%s] ==========\n", stage)
	fmt.Printf("TripID: %d | Status: %s\n", snapshot.TripID, snapshot.TripStatus)
	fmt.Printf("Capacity: Total=%d Available=%d Occupied=%d Pending=%d\n",
		snapshot.Capacity.TotalSeats,
		snapshot.Capacity.AvailableSeats,
		snapshot.Capacity.OccupiedSeats,
		snapshot.Capacity.PendingPaymentSeats)
	fmt.Printf("Financial: TotalPaid=%.2f TotalPending=%.2f\n",
		snapshot.Capacity.TotalAmountPaid,
		snapshot.Capacity.TotalAmountPending)
	fmt.Printf("Summary: TotalTickets=%d PaidTickets=%d PendingPayments=%d\n",
		snapshot.Summary.TotalTickets,
		snapshot.Summary.PaidTickets,
		snapshot.Summary.PendingPayments)
	fmt.Printf("Locations: %d\n", len(snapshot.Locations))
	for _, loc := range snapshot.Locations {
		fmt.Printf("  [%s] %s: Pickup=%d Dropoff=%d Pending=%d AvailableFromHere=%d | Paid=%.2f Pending=%.2f\n",
			loc.Type, loc.LocationID, loc.Seats.Pickup, loc.Seats.Dropoff,
			loc.Seats.PendingPayment, loc.Seats.AvailableFromHere,
			loc.Seats.TotalAmountPaid, loc.Seats.TotalAmountPending)
	}
	fmt.Printf("===============================================\n\n")
}
