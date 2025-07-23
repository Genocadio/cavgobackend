package repository

import (
	"cavgotrips/internal/models"
	"encoding/json"
	"log"
	"time"

	"gorm.io/gorm"
)

type SSESessionRepository interface {
	Create(session *models.SSESession) error
	GetByUUID(uuid string) (*models.SSESession, error)
	UpdateTripIDs(uuid string, tripIDs []int64) error
	AddTripIDs(uuid string, tripIDs []int64) error
	RemoveTripIDs(uuid string, tripIDs []int64) error
	DeleteExpired() error
	GetAllActive() ([]models.SSESession, error)
	GetSessionsByTripID(tripID int64) ([]models.SSESession, error)
}

type sseSessionRepository struct {
	db *gorm.DB
}

func NewSSESessionRepository(db *gorm.DB) SSESessionRepository {
	return &sseSessionRepository{db: db}
}

func (r *sseSessionRepository) Create(session *models.SSESession) error {
	return r.db.Create(session).Error
}

func (r *sseSessionRepository) GetByUUID(uuid string) (*models.SSESession, error) {
	var session models.SSESession
	err := r.db.Where("uuid = ? AND expires_at > ?", uuid, time.Now()).First(&session).Error
	if err != nil {
		return nil, err
	}
	return &session, nil
}

func (r *sseSessionRepository) UpdateTripIDs(uuid string, tripIDs []int64) error {
	// Convert tripIDs to JSON string for JSONB column
	tripIDsJSON, err := json.Marshal(tripIDs)
	if err != nil {
		return err
	}

	return r.db.Model(&models.SSESession{}).
		Where("uuid = ?", uuid).
		Update("trip_ids", tripIDsJSON).Error
}

func (r *sseSessionRepository) AddTripIDs(uuid string, tripIDs []int64) error {
	// Get current session
	session, err := r.GetByUUID(uuid)
	if err != nil {
		return err
	}

	// Create map of existing trip IDs
	tripIDMap := make(map[int64]bool)
	for _, id := range session.TripIDs {
		tripIDMap[id] = true
	}

	// Add new trip IDs
	for _, id := range tripIDs {
		tripIDMap[id] = true
	}

	// Convert back to slice
	newTripIDs := make([]int64, 0, len(tripIDMap))
	for id := range tripIDMap {
		newTripIDs = append(newTripIDs, id)
	}

	return r.UpdateTripIDs(uuid, newTripIDs)
}

func (r *sseSessionRepository) RemoveTripIDs(uuid string, tripIDs []int64) error {
	// Get current session
	session, err := r.GetByUUID(uuid)
	if err != nil {
		return err
	}

	// Create map of existing trip IDs
	tripIDMap := make(map[int64]bool)
	for _, id := range session.TripIDs {
		tripIDMap[id] = true
	}

	// Remove specified trip IDs
	for _, id := range tripIDs {
		delete(tripIDMap, id)
	}

	// Convert back to slice
	newTripIDs := make([]int64, 0, len(tripIDMap))
	for id := range tripIDMap {
		newTripIDs = append(newTripIDs, id)
	}

	return r.UpdateTripIDs(uuid, newTripIDs)
}

func (r *sseSessionRepository) DeleteExpired() error {
	return r.db.Where("expires_at <= ?", time.Now()).Delete(&models.SSESession{}).Error
}

func (r *sseSessionRepository) GetAllActive() ([]models.SSESession, error) {
	var sessions []models.SSESession
	err := r.db.Where("expires_at > ?", time.Now()).Find(&sessions).Error
	return sessions, err
}

func (r *sseSessionRepository) GetSessionsByTripID(tripID int64) ([]models.SSESession, error) {
	var sessions []models.SSESession

	// Try the JSONB contains query first
	err := r.db.Where("expires_at > ? AND trip_ids @> ?", time.Now(), []int64{tripID}).Find(&sessions).Error
	if err != nil {
		log.Printf("[Repository] ❌ JSONB query failed for trip %d: %v", tripID, err)
		return sessions, err
	}

	log.Printf("[Repository] 🔍 JSONB query found %d sessions for trip %d", len(sessions), tripID)

	// If no results, try a different approach - get all active sessions and filter in Go
	if len(sessions) == 0 {
		log.Printf("[Repository] 🔍 No sessions found with JSONB query, trying alternative approach")
		var allSessions []models.SSESession
		err := r.db.Where("expires_at > ?", time.Now()).Find(&allSessions).Error
		if err != nil {
			log.Printf("[Repository] ❌ Failed to get all active sessions: %v", err)
			return sessions, err
		}

		log.Printf("[Repository] 🔍 Found %d total active sessions", len(allSessions))

		// Filter sessions that contain the trip ID
		for _, session := range allSessions {
			for _, sessionTripID := range session.TripIDs {
				if sessionTripID == tripID {
					sessions = append(sessions, session)
					log.Printf("[Repository] ✅ Session %s contains trip %d", session.UUID, tripID)
					break
				}
			}
		}

		log.Printf("[Repository] 🔍 Alternative approach found %d sessions for trip %d", len(sessions), tripID)
	}

	return sessions, nil
}
