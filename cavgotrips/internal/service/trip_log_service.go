package service

import (
	"cavgotrips/internal/models"
	"cavgotrips/internal/repository"
	"encoding/json"
	"log"
	"time"
)

type TripLogService struct {
	repo      repository.TripLogRepository
	enabled   bool
}

func NewTripLogService(repo repository.TripLogRepository, enabled bool) *TripLogService {
	return &TripLogService{
		repo:    repo,
		enabled: enabled,
	}
}

// LogTripUpdate logs a full trip snapshot and returns the created trip log ID
func (s *TripLogService) LogTripUpdate(trip *models.Trip, updateType string) (int64, error) {
	if !s.enabled {
		return 0, nil
	}

	// Create a deep copy of the trip to avoid any reference issues
	tripJSON, err := json.Marshal(trip)
	if err != nil {
		log.Printf("[TripLogService] Failed to marshal trip: %v", err)
		return 0, err
	}

	var tripSnapshot models.Trip
	if err := json.Unmarshal(tripJSON, &tripSnapshot); err != nil {
		log.Printf("[TripLogService] Failed to unmarshal trip: %v", err)
		return 0, err
	}

	// Clear relationships to avoid circular references in JSONB
	tripSnapshot.Route.Waypoints = nil

	tripLog := &models.TripLog{
		TripID:        trip.ID,
		TripSnapshot:  tripSnapshot,
		UpdateType:    updateType,
		TripCreatedAt: trip.CreatedAt,
		LoggedAt:      time.Now(),
	}

	if err := s.repo.Create(tripLog); err != nil {
		log.Printf("[TripLogService] Failed to create trip log: %v", err)
		return 0, err
	}

	log.Printf("[TripLogService] Logged trip %d update: %s (log ID: %d)", trip.ID, updateType, tripLog.ID)
	return tripLog.ID, nil
}

// LogWaypointUpdate logs a waypoint update separately
// If tripLogID is provided (non-zero), it uses that; otherwise it finds the most recent trip log
func (s *TripLogService) LogWaypointUpdate(waypoint *models.TripWaypoint, tripID int64, updateType string, tripLogID ...int64) error {
	if !s.enabled {
		return nil
	}

	var finalTripLogID int64

	// If tripLogID is provided, use it; otherwise find the most recent trip log
	if len(tripLogID) > 0 && tripLogID[0] > 0 {
		finalTripLogID = tripLogID[0]
	} else {
		// Get the most recent trip log for this trip to link the waypoint log
		tripLogs, err := s.repo.GetByTripID(tripID)
		if err != nil {
			log.Printf("[TripLogService] Failed to get trip logs for trip %d: %v", tripID, err)
			return err
		}
		if len(tripLogs) == 0 {
			log.Printf("[TripLogService] No trip log found for trip %d, skipping waypoint log", tripID)
			return nil
		}
		// Use the most recent trip log (first one since they're ordered DESC)
		finalTripLogID = tripLogs[0].ID
	}

	// Create a deep copy of the waypoint
	waypointJSON, err := json.Marshal(waypoint)
	if err != nil {
		log.Printf("[TripLogService] Failed to marshal waypoint: %v", err)
		return err
	}

	var waypointSnapshot models.TripWaypoint
	if err := json.Unmarshal(waypointJSON, &waypointSnapshot); err != nil {
		log.Printf("[TripLogService] Failed to unmarshal waypoint: %v", err)
		return err
	}

	// Clear relationships to avoid circular references
	waypointSnapshot.Location = models.Location{}

	waypointLog := &models.TripWaypointLog{
		TripLogID:        finalTripLogID,
		WaypointID:       waypoint.ID,
		WaypointSnapshot: waypointSnapshot,
		UpdateType:       updateType,
		LoggedAt:         time.Now(),
	}

	if err := s.repo.CreateWaypointLog(waypointLog); err != nil {
		log.Printf("[TripLogService] Failed to create waypoint log: %v", err)
		return err
	}

	log.Printf("[TripLogService] Logged waypoint %d update: %s for trip %d", waypoint.ID, updateType, tripID)
	return nil
}

// GetTripLogs retrieves all logs for a specific trip
func (s *TripLogService) GetTripLogs(tripID int64) ([]models.TripLog, error) {
	if !s.enabled {
		return nil, nil
	}
	return s.repo.GetByTripID(tripID)
}

// StartCleanupScheduler starts a daily check for cleanup on the 30th
func (s *TripLogService) StartCleanupScheduler() {
	if !s.enabled {
		return
	}

	go func() {
		ticker := time.NewTicker(24 * time.Hour)
		defer ticker.Stop()

		// Run immediately on startup if it's the 30th
		if time.Now().Day() == 30 {
			s.runCleanup()
		}

		for range ticker.C {
			if time.Now().Day() == 30 {
				s.runCleanup()
			}
		}
	}()
}

// runCleanup performs the cleanup operation
func (s *TripLogService) runCleanup() {
	now := time.Now()
	
	// Calculate date range: from 28th of previous month to 27th of current month (inclusive)
	// If current month is January, previous month is December of previous year
	prevMonth := now.AddDate(0, -1, 0)
	
	// Start date: 28th of previous month at 00:00:00 (inclusive)
	startDate := time.Date(prevMonth.Year(), prevMonth.Month(), 28, 0, 0, 0, 0, now.Location())
	
	// End date: 28th of current month at 00:00:00 (exclusive, so it includes up to 27th 23:59:59)
	endDate := time.Date(now.Year(), now.Month(), 28, 0, 0, 0, 0, now.Location())

	log.Printf("[TripLogService] Running cleanup: deleting logs for trips created from %s to %s (exclusive)", 
		startDate.Format("2006-01-02 15:04:05"), endDate.Format("2006-01-02 15:04:05"))

	if err := s.repo.DeleteLogsByDateRange(startDate, endDate); err != nil {
		log.Printf("[TripLogService] Cleanup failed: %v", err)
	} else {
		log.Printf("[TripLogService] Cleanup completed successfully")
	}
}

