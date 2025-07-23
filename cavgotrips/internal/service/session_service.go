package service

import (
	"cavgotrips/internal/models"
	"cavgotrips/internal/repository"
	"crypto/rand"
	"encoding/hex"
	"log"
	"sync"
	"time"
)

type SessionService struct {
	sessionRepo repository.SSESessionRepository
	mutex       sync.RWMutex
}

func NewSessionService(sessionRepo repository.SSESessionRepository) *SessionService {
	service := &SessionService{
		sessionRepo: sessionRepo,
	}

	// Start background cleanup
	go service.cleanupExpiredSessions()

	return service
}

func (s *SessionService) CreateSession(tripIDs []int64) *models.SSESession {
	uuid := generateUUID()
	expiresAt := time.Now().Add(10 * time.Minute)

	session := &models.SSESession{
		UUID:      uuid,
		TripIDs:   tripIDs,
		ExpiresAt: expiresAt,
	}

	err := s.sessionRepo.Create(session)
	if err != nil {
		log.Printf("[Session] ❌ Failed to create session %s: %v", uuid, err)
		return nil
	}

	log.Printf("[Session] ✅ Created session %s with %d trip IDs", uuid, len(tripIDs))
	return session
}

func (s *SessionService) GetSession(uuid string) *models.SSESession {
	session, err := s.sessionRepo.GetByUUID(uuid)
	if err != nil {
		log.Printf("[Session] ❌ Session %s not found or expired: %v", uuid, err)
		return nil
	}

	log.Printf("[Session] ✅ Retrieved session %s with %d trip IDs", uuid, len(session.TripIDs))
	return session
}

func (s *SessionService) UpdateSession(uuid string, tripIDs []int64) bool {
	err := s.sessionRepo.UpdateTripIDs(uuid, tripIDs)
	if err != nil {
		log.Printf("[Session] ❌ Failed to update session %s: %v", uuid, err)
		return false
	}

	log.Printf("[Session] ✅ Updated session %s with %d trip IDs", uuid, len(tripIDs))
	return true
}

func (s *SessionService) AddTripIDs(uuid string, tripIDs []int64) bool {
	err := s.sessionRepo.AddTripIDs(uuid, tripIDs)
	if err != nil {
		log.Printf("[Session] ❌ Failed to add trip IDs to session %s: %v", uuid, err)
		return false
	}

	log.Printf("[Session] ✅ Added %d trip IDs to session %s", len(tripIDs), uuid)
	return true
}

func (s *SessionService) RemoveTripIDs(uuid string, tripIDs []int64) bool {
	err := s.sessionRepo.RemoveTripIDs(uuid, tripIDs)
	if err != nil {
		log.Printf("[Session] ❌ Failed to remove trip IDs from session %s: %v", uuid, err)
		return false
	}

	log.Printf("[Session] ✅ Removed %d trip IDs from session %s", len(tripIDs), uuid)
	return true
}

func (s *SessionService) RemoveSession(uuid string) bool {
	// This would need a Delete method in the repository
	// For now, we'll just log it
	log.Printf("[Session] 📝 Session %s marked for removal", uuid)
	return true
}

func (s *SessionService) GetActiveSessionsCount() int {
	sessions, err := s.sessionRepo.GetAllActive()
	if err != nil {
		log.Printf("[Session] ❌ Failed to get active sessions count: %v", err)
		return 0
	}
	return len(sessions)
}

func (s *SessionService) GetSessionsByTripID(tripID int64) []models.SSESession {
	sessions, err := s.sessionRepo.GetSessionsByTripID(tripID)
	if err != nil {
		log.Printf("[Session] ❌ Failed to get sessions for trip %d: %v", tripID, err)
		return []models.SSESession{}
	}

	log.Printf("[Session] 🔍 Found %d sessions tracking trip %d", len(sessions), tripID)
	for _, session := range sessions {
		log.Printf("[Session] 📋 Session %s tracks trip %d with %d total trip IDs", session.UUID, tripID, len(session.TripIDs))
	}

	return sessions
}

func (s *SessionService) cleanupExpiredSessions() {
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		err := s.sessionRepo.DeleteExpired()
		if err != nil {
			log.Printf("[Session] ❌ Failed to cleanup expired sessions: %v", err)
		} else {
			log.Printf("[Session] 🧹 Cleaned up expired sessions")
		}
	}
}

func generateUUID() string {
	bytes := make([]byte, 16)
	rand.Read(bytes)
	return hex.EncodeToString(bytes)
}
