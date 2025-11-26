package service

import (
	"cavgotrips/internal/models"
	"cavgotrips/internal/repository"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"sort"
	"sync"
	"time"
)

type ChangeTrackingService struct {
	repo              repository.ChangeTrackingRepository
	currentBatch      *models.ChangeBatch
	batchMutex        sync.Mutex
	mergeTimer        *time.Timer
	mergeTimerMutex   sync.Mutex
	lastChangeTime    time.Time
	lastChangeMutex   sync.Mutex
}

func NewChangeTrackingService(repo repository.ChangeTrackingRepository) *ChangeTrackingService {
	return &ChangeTrackingService{
		repo: repo,
	}
}

// RecordChange creates a change record in current unmerged batch (or creates new batch if none exists)
func (s *ChangeTrackingService) RecordChange(changedType string, changedID int64, isDeleted bool) error {
	s.batchMutex.Lock()
	defer s.batchMutex.Unlock()

	// Get or create current unmerged batch
	if s.currentBatch == nil {
		batch, err := s.repo.CreateChangeBatch()
		if err != nil {
			return err
		}
		s.currentBatch = batch
	}

	// Create change record
	err := s.repo.CreateChange(s.currentBatch.ID, changedType, changedID, isDeleted)
	if err != nil {
		return err
	}

	// Update last change time
	s.lastChangeMutex.Lock()
	s.lastChangeTime = time.Now()
	s.lastChangeMutex.Unlock()

	// Start/reset merge timer
	s.StartMergeTimer()

	return nil
}

// MergeBatches merges unmerged batches into main hash
func (s *ChangeTrackingService) MergeBatches() error {
	log.Printf("[ChangeTracking] Starting merge operation")

	// Get all unmerged batches
	batches, err := s.repo.GetUnmergedBatches()
	if err != nil {
		return err
	}

	if len(batches) == 0 {
		log.Printf("[ChangeTracking] No unmerged batches to merge")
		return nil
	}

	log.Printf("[ChangeTracking] Found %d unmerged batches", len(batches))

	// Collect all changes from all batches
	locationIDs := make(map[int64]bool)
	routeIDs := make(map[int64]bool)
	batchIDs := make([]int64, len(batches))

	for i, batch := range batches {
		batchIDs[i] = batch.ID

		// Get all changes in this batch
		changes, err := s.repo.GetChangesByBatchID(batch.ID)
		if err != nil {
			return err
		}

		for _, change := range changes {
			if change.ChangedType == "location" {
				locationIDs[change.ChangedID] = true
			} else if change.ChangedType == "route" {
				routeIDs[change.ChangedID] = true
			}
		}
	}

	// Convert maps to sorted arrays
	locationIDArray := make([]int64, 0, len(locationIDs))
	for id := range locationIDs {
		locationIDArray = append(locationIDArray, id)
	}
	sort.Slice(locationIDArray, func(i, j int) bool {
		return locationIDArray[i] < locationIDArray[j]
	})

	routeIDArray := make([]int64, 0, len(routeIDs))
	for id := range routeIDs {
		routeIDArray = append(routeIDArray, id)
	}
	sort.Slice(routeIDArray, func(i, j int) bool {
		return routeIDArray[i] < routeIDArray[j]
	})

	// Compute main hash
	hashData := map[string][]int64{
		"locations": locationIDArray,
		"routes":    routeIDArray,
	}
	hashJSON, err := json.Marshal(hashData)
	if err != nil {
		return err
	}

	hashBytes := sha256.Sum256(hashJSON)
	hash := hex.EncodeToString(hashBytes[:])

	log.Printf("[ChangeTracking] Computed main hash: %s (locations: %d, routes: %d)", hash, len(locationIDArray), len(routeIDArray))

	// Create main hash record
	mainHash := &models.MainHash{
		Hash:            hash,
		LocationIDs:     locationIDArray,
		RouteIDs:        routeIDArray,
		IncludedBatches: batchIDs,
		Type:            "auto",
	}

	err = s.repo.CreateMainHash(mainHash)
	if err != nil {
		return err
	}

	// Mark batches as merged
	err = s.repo.MarkBatchesAsMerged(batchIDs)
	if err != nil {
		return err
	}

	// Clear current batch
	s.batchMutex.Lock()
	s.currentBatch = nil
	s.batchMutex.Unlock()

	// Stop merge timer
	s.StopMergeTimer()

	log.Printf("[ChangeTracking] Merge completed successfully")

	return nil
}

// GetLatestMainHash returns the latest main hash
func (s *ChangeTrackingService) GetLatestMainHash() (*models.MainHash, error) {
	return s.repo.GetLatestMainHash()
}

// CompareHash compares client hash with current main hash
func (s *ChangeTrackingService) CompareHash(clientHash string) (bool, *models.MainHash, *models.MainHash) {
	currentMainHash, err := s.GetLatestMainHash()
	if err != nil || currentMainHash == nil {
		// No main hash exists yet
		return false, nil, nil
	}

	clientMainHash, err := s.repo.GetMainHashByHash(clientHash)
	if err != nil || clientMainHash == nil {
		// Client hash not found
		return false, nil, currentMainHash
	}

	matches := currentMainHash.Hash == clientHash
	return matches, clientMainHash, currentMainHash
}

// GetChangedRoutesSinceHash returns changed routes, deleted IDs, and total count
func (s *ChangeTrackingService) GetChangedRoutesSinceHash(hash string, limit, offset int) ([]models.Route, []int64, int64, error) {
	clientMainHash, err := s.repo.GetMainHashByHash(hash)
	if err != nil {
		return nil, nil, 0, err
	}

	if clientMainHash == nil {
		// Hash not found - this should be caught by handler, but handle gracefully
		return nil, nil, 0, fmt.Errorf("hash not found: %s", hash)
	}

	// Get changed routes since this main hash
	routes, total, err := s.repo.GetChangedRoutesSinceMainHash(clientMainHash.ID, limit, offset)
	if err != nil {
		return nil, nil, 0, err
	}

	// Get deleted IDs
	deletedIDs, err := s.repo.GetDeletedIDsSinceMainHash(clientMainHash.ID, "route")
	if err != nil {
		return nil, nil, 0, err
	}

	return routes, deletedIDs, total, nil
}

// GetChangedLocationsSinceHash returns changed locations, deleted IDs, and total count
func (s *ChangeTrackingService) GetChangedLocationsSinceHash(hash string, limit, offset int) ([]models.Location, []int64, int64, error) {
	clientMainHash, err := s.repo.GetMainHashByHash(hash)
	if err != nil {
		return nil, nil, 0, err
	}

	if clientMainHash == nil {
		// Hash not found - this should be caught by handler, but handle gracefully
		return nil, nil, 0, fmt.Errorf("hash not found: %s", hash)
	}

	// Get changed locations since this main hash
	locations, total, err := s.repo.GetChangedLocationsSinceMainHash(clientMainHash.ID, limit, offset)
	if err != nil {
		return nil, nil, 0, err
	}

	// Get deleted IDs
	deletedIDs, err := s.repo.GetDeletedIDsSinceMainHash(clientMainHash.ID, "location")
	if err != nil {
		return nil, nil, 0, err
	}

	return locations, deletedIDs, total, nil
}

// StartMergeTimer starts/resets merge timer (called by RecordChange)
func (s *ChangeTrackingService) StartMergeTimer() {
	s.mergeTimerMutex.Lock()
	defer s.mergeTimerMutex.Unlock()

	// Stop existing timer if any
	if s.mergeTimer != nil {
		s.mergeTimer.Stop()
	}

	// Start new timer for 2 hours
	s.mergeTimer = time.AfterFunc(2*time.Hour, func() {
		log.Printf("[ChangeTracking] 2-hour timer expired, triggering merge")
		if err := s.MergeBatches(); err != nil {
			log.Printf("[ChangeTracking] Error during timer-triggered merge: %v", err)
		}
	})

	log.Printf("[ChangeTracking] Merge timer started/reset (2 hours)")
}

// StopMergeTimer stops merge timer (called after merge completes)
func (s *ChangeTrackingService) StopMergeTimer() {
	s.mergeTimerMutex.Lock()
	defer s.mergeTimerMutex.Unlock()

	if s.mergeTimer != nil {
		s.mergeTimer.Stop()
		s.mergeTimer = nil
		log.Printf("[ChangeTracking] Merge timer stopped")
	}
}

// GetLastChangeTime returns the last change timestamp
func (s *ChangeTrackingService) GetLastChangeTime() time.Time {
	s.lastChangeMutex.Lock()
	defer s.lastChangeMutex.Unlock()
	return s.lastChangeTime
}

// StartInactivityMonitor starts monitoring for inactivity (10 minutes)
func (s *ChangeTrackingService) StartInactivityMonitor() {
	go func() {
		ticker := time.NewTicker(1 * time.Minute)
		defer ticker.Stop()

		for range ticker.C {
			s.lastChangeMutex.Lock()
			lastChange := s.lastChangeTime
			s.lastChangeMutex.Unlock()

			if !lastChange.IsZero() {
				inactivityDuration := time.Since(lastChange)
				if inactivityDuration >= 10*time.Minute {
					log.Printf("[ChangeTracking] 10 minutes of inactivity detected, triggering merge")
					if err := s.MergeBatches(); err != nil {
						log.Printf("[ChangeTracking] Error during inactivity-triggered merge: %v", err)
					}
					// Reset last change time to prevent immediate re-trigger
					s.lastChangeMutex.Lock()
					s.lastChangeTime = time.Time{}
					s.lastChangeMutex.Unlock()
				}
			}
		}
	}()
}

