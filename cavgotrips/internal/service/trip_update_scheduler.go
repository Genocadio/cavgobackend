package service

import (
	"context"
	"log"
	"sync"
	"time"
)

type TimerEntry struct {
	CompanyID    int64
	ExpiresAt    time.Time
	BaseURL      string
	TripIDs      map[int64]bool
	Context      context.Context
	CancelFunc   context.CancelFunc
	mu           sync.RWMutex
}

type TripUpdateScheduler struct {
	timers map[int64]*TimerEntry
	mu     sync.RWMutex
	stopCh chan struct{}
}

func NewTripUpdateScheduler() *TripUpdateScheduler {
	scheduler := &TripUpdateScheduler{
		timers: make(map[int64]*TimerEntry),
		stopCh: make(chan struct{}),
	}
	
	// Start background goroutine to check for expired timers
	go scheduler.checkExpiredTimers()
	
	return scheduler
}

// StartOrExtendTimer starts a new timer or extends existing one if within 3 minutes
func (s *TripUpdateScheduler) StartOrExtendTimer(companyID int64, baseURL string, tripIDs []int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	
	now := time.Now()
	existingTimer, exists := s.timers[companyID]
	
	if exists {
		// Check if existing timer is within 3 minutes of creation
		timeSinceCreation := now.Sub(existingTimer.ExpiresAt.Add(-10 * time.Minute))
		if timeSinceCreation < 3*time.Minute {
			// Extend the timer to 10 minutes from now
			existingTimer.mu.Lock()
			existingTimer.ExpiresAt = now.Add(10 * time.Minute)
			// Add new trip IDs to the set
			for _, tripID := range tripIDs {
				existingTimer.TripIDs[tripID] = true
			}
			existingTimer.mu.Unlock()
			log.Printf("[TripUpdateScheduler] Extended timer for company %d, expires at %v", companyID, existingTimer.ExpiresAt)
			return
		}
		// Existing timer is too old, cancel it and create new one
		existingTimer.CancelFunc()
		delete(s.timers, companyID)
	}
	
	// Create new timer
	ctx, cancel := context.WithCancel(context.Background())
	tripIDSet := make(map[int64]bool)
	for _, tripID := range tripIDs {
		tripIDSet[tripID] = true
	}
	
	timer := &TimerEntry{
		CompanyID:  companyID,
		ExpiresAt:  now.Add(10 * time.Minute),
		BaseURL:    baseURL,
		TripIDs:    tripIDSet,
		Context:    ctx,
		CancelFunc: cancel,
	}
	
	s.timers[companyID] = timer
	log.Printf("[TripUpdateScheduler] Started new timer for company %d, expires at %v", companyID, timer.ExpiresAt)
}

// StopTimer stops and removes a timer
func (s *TripUpdateScheduler) StopTimer(companyID int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	
	timer, exists := s.timers[companyID]
	if exists {
		timer.CancelFunc()
		delete(s.timers, companyID)
		log.Printf("[TripUpdateScheduler] Stopped timer for company %d", companyID)
	}
}

// IsTimerActive checks if a timer exists and is still active
func (s *TripUpdateScheduler) IsTimerActive(companyID int64) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	
	timer, exists := s.timers[companyID]
	if !exists {
		return false
	}
	
	timer.mu.RLock()
	defer timer.mu.RUnlock()
	
	return time.Now().Before(timer.ExpiresAt)
}

// GetTimer returns the timer entry for a company (if active)
func (s *TripUpdateScheduler) GetTimer(companyID int64) (*TimerEntry, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	
	timer, exists := s.timers[companyID]
	if !exists {
		return nil, false
	}
	
	timer.mu.RLock()
	defer timer.mu.RUnlock()
	
	if time.Now().After(timer.ExpiresAt) {
		return nil, false
	}
	
	return timer, true
}

// AddTripID adds a trip ID to an active timer
func (s *TripUpdateScheduler) AddTripID(companyID int64, tripID int64) bool {
	timer, exists := s.GetTimer(companyID)
	if !exists {
		return false
	}
	
	timer.mu.Lock()
	defer timer.mu.Unlock()
	timer.TripIDs[tripID] = true
	return true
}

// checkExpiredTimers runs in background and removes expired timers
func (s *TripUpdateScheduler) checkExpiredTimers() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	
	for {
		select {
		case <-ticker.C:
			s.mu.Lock()
			now := time.Now()
			for companyID, timer := range s.timers {
				timer.mu.RLock()
				expired := now.After(timer.ExpiresAt)
				timer.mu.RUnlock()
				
				if expired {
					timer.CancelFunc()
					delete(s.timers, companyID)
					log.Printf("[TripUpdateScheduler] Timer expired and removed for company %d", companyID)
				}
			}
			s.mu.Unlock()
		case <-s.stopCh:
			return
		}
	}
}

// Stop stops the scheduler
func (s *TripUpdateScheduler) Stop() {
	close(s.stopCh)
	s.mu.Lock()
	defer s.mu.Unlock()
	
	for companyID, timer := range s.timers {
		timer.CancelFunc()
		delete(s.timers, companyID)
	}
}



