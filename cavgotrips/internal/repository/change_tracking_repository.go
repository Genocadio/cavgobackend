package repository

import (
	"cavgotrips/internal/models"
	"sort"

	"gorm.io/gorm"
)

type ChangeTrackingRepository interface {
	CreateChangeBatch() (*models.ChangeBatch, error)
	CreateChange(batchID int64, changedType string, changedID int64, isDeleted bool, operation string) error
	GetUnmergedBatches() ([]models.ChangeBatch, error)
	GetChangesByBatchID(batchID int64) ([]models.Change, error)
	MarkBatchesAsMerged(batchIDs []int64) error
	GetLatestMainHash() (*models.MainHash, error)
	GetMainHashByHash(hash string) (*models.MainHash, error)
	CreateMainHash(mainHash *models.MainHash) error
	GetChangesSinceMainHash(mainHashID int64, changedType string) ([]models.Change, error)
	GetLatestEntityChangesSinceMainHash(mainHashID int64, changedType string, limit, offset int) ([]models.Change, int64, error)
	GetRoutesByIDs(ids []int64) ([]models.Route, error)
	GetLocationsByIDs(ids []int64) ([]models.Location, error)
	GetChangedIDsSinceMainHash(mainHashID int64, changedType string) ([]int64, error)
	GetDeletedIDsSinceMainHash(mainHashID int64, changedType string) ([]int64, error)
	GetChangedRoutesSinceMainHash(mainHashID int64, limit, offset int) ([]models.Route, int64, error)
	GetChangedLocationsSinceMainHash(mainHashID int64, limit, offset int) ([]models.Location, int64, error)
}

type changeTrackingRepository struct {
	db *gorm.DB
}

func NewChangeTrackingRepository(db *gorm.DB) ChangeTrackingRepository {
	return &changeTrackingRepository{db: db}
}

func (r *changeTrackingRepository) CreateChangeBatch() (*models.ChangeBatch, error) {
	batch := &models.ChangeBatch{}
	if err := r.db.Create(batch).Error; err != nil {
		return nil, err
	}
	return batch, nil
}

func (r *changeTrackingRepository) CreateChange(batchID int64, changedType string, changedID int64, isDeleted bool, operation string) error {
	if operation == "" {
		if isDeleted {
			operation = models.ChangeOperationDeleted
		} else {
			operation = models.ChangeOperationUpdated
		}
	}
	if isDeleted {
		operation = models.ChangeOperationDeleted
	}

	change := &models.Change{
		ChangeBatchID: batchID,
		ChangedType:   changedType,
		ChangedID:     changedID,
		IsDeleted:     isDeleted,
		Operation:     operation,
	}
	return r.db.Create(change).Error
}

func (r *changeTrackingRepository) GetUnmergedBatches() ([]models.ChangeBatch, error) {
	var batches []models.ChangeBatch
	err := r.db.Where("merged = ?", false).Order("created_at ASC").Find(&batches).Error
	return batches, err
}

func (r *changeTrackingRepository) GetChangesByBatchID(batchID int64) ([]models.Change, error) {
	var changes []models.Change
	err := r.db.Where("change_batch_id = ?", batchID).Find(&changes).Error
	return changes, err
}

func (r *changeTrackingRepository) MarkBatchesAsMerged(batchIDs []int64) error {
	return r.db.Model(&models.ChangeBatch{}).
		Where("id IN ?", batchIDs).
		Update("merged", true).Error
}

func (r *changeTrackingRepository) GetLatestMainHash() (*models.MainHash, error) {
	var mainHash models.MainHash
	err := r.db.Order("created_at DESC").First(&mainHash).Error
	if err == gorm.ErrRecordNotFound {
		return nil, nil
	}
	return &mainHash, err
}

func (r *changeTrackingRepository) GetMainHashByHash(hash string) (*models.MainHash, error) {
	var mainHash models.MainHash
	err := r.db.Where("hash = ?", hash).First(&mainHash).Error
	if err == gorm.ErrRecordNotFound {
		return nil, nil
	}
	return &mainHash, err
}

func (r *changeTrackingRepository) CreateMainHash(mainHash *models.MainHash) error {
	return r.db.Create(mainHash).Error
}

func (r *changeTrackingRepository) GetChangesSinceMainHash(mainHashID int64, changedType string) ([]models.Change, error) {
	var mainHash models.MainHash
	if err := r.db.First(&mainHash, mainHashID).Error; err != nil {
		return nil, err
	}

	var changes []models.Change
	// Return all changes after the provided hash creation time.
	// Including both merged and unmerged batches guarantees clients can catch up
	// from older hashes without losing historical changes.
	err := r.db.Where("changed_type = ? AND created_at > ?", changedType, mainHash.CreatedAt).
		Order("created_at ASC, id ASC").
		Select("changes.*").
		Find(&changes).Error
	return changes, err
}

func (r *changeTrackingRepository) GetLatestEntityChangesSinceMainHash(mainHashID int64, changedType string, limit, offset int) ([]models.Change, int64, error) {
	changes, err := r.GetChangesSinceMainHash(mainHashID, changedType)
	if err != nil {
		return nil, 0, err
	}

	if len(changes) == 0 {
		return []models.Change{}, 0, nil
	}

	latestByID := make(map[int64]models.Change)
	for _, change := range changes {
		latestByID[change.ChangedID] = change
	}

	ids := make([]int64, 0, len(latestByID))
	for id := range latestByID {
		ids = append(ids, id)
	}
	sort.Slice(ids, func(i, j int) bool {
		return ids[i] < ids[j]
	})

	total := int64(len(ids))
	if offset >= len(ids) {
		return []models.Change{}, total, nil
	}

	end := len(ids)
	if limit > 0 && offset+limit < end {
		end = offset + limit
	}

	result := make([]models.Change, 0, end-offset)
	for _, id := range ids[offset:end] {
		result = append(result, latestByID[id])
	}

	return result, total, nil
}

func (r *changeTrackingRepository) GetRoutesByIDs(ids []int64) ([]models.Route, error) {
	if len(ids) == 0 {
		return []models.Route{}, nil
	}

	var routes []models.Route
	err := r.db.Preload("Origin").Preload("Destination").
		Where("id IN ?", ids).
		Order("id ASC").
		Find(&routes).Error
	if err != nil {
		return nil, err
	}

	for i := range routes {
		routes[i].Waypoints = nil
	}

	return routes, nil
}

func (r *changeTrackingRepository) GetLocationsByIDs(ids []int64) ([]models.Location, error) {
	if len(ids) == 0 {
		return []models.Location{}, nil
	}

	var locations []models.Location
	err := r.db.Where("id IN ?", ids).
		Order("id ASC").
		Find(&locations).Error
	if err != nil {
		return nil, err
	}

	return locations, nil
}

func (r *changeTrackingRepository) GetChangedIDsSinceMainHash(mainHashID int64, changedType string) ([]int64, error) {
	changes, err := r.GetChangesSinceMainHash(mainHashID, changedType)
	if err != nil {
		return nil, err
	}

	latestByID := make(map[int64]models.Change)
	for _, change := range changes {
		latestByID[change.ChangedID] = change
	}

	ids := make([]int64, 0, len(latestByID))
	for id, change := range latestByID {
		if change.Operation == models.ChangeOperationDeleted || change.IsDeleted {
			continue
		}
		ids = append(ids, id)
	}
	sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })

	return ids, nil
}

func (r *changeTrackingRepository) GetDeletedIDsSinceMainHash(mainHashID int64, changedType string) ([]int64, error) {
	var mainHash models.MainHash
	if err := r.db.First(&mainHash, mainHashID).Error; err != nil {
		return nil, err
	}

	var changes []models.Change
	err := r.db.Where("changed_type = ? AND created_at > ?", changedType, mainHash.CreatedAt).
		Order("created_at ASC, id ASC").
		Find(&changes).Error
	if err != nil {
		return nil, err
	}

	latestByID := make(map[int64]models.Change)
	for _, change := range changes {
		latestByID[change.ChangedID] = change
	}

	ids := make([]int64, 0, len(latestByID))
	for id, change := range latestByID {
		if change.Operation == models.ChangeOperationDeleted || change.IsDeleted {
			ids = append(ids, id)
		}
	}
	sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })

	return ids, nil
}

func (r *changeTrackingRepository) GetChangedRoutesSinceMainHash(mainHashID int64, limit, offset int) ([]models.Route, int64, error) {
	changedIDs, err := r.GetChangedIDsSinceMainHash(mainHashID, "route")
	if err != nil {
		return nil, 0, err
	}

	if len(changedIDs) == 0 {
		return []models.Route{}, 0, nil
	}

	var routes []models.Route
	var total int64

	// Get total count
	err = r.db.Model(&models.Route{}).Where("id IN ?", changedIDs).Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	// Get paginated results - only preload origin and destination, not waypoints
	err = r.db.Preload("Origin").Preload("Destination").
		Where("id IN ?", changedIDs).
		Order("id ASC").
		Limit(limit).
		Offset(offset).
		Find(&routes).Error

	// Clear waypoints to return minimal data (location_id refs only)
	for i := range routes {
		routes[i].Waypoints = nil
	}

	return routes, total, err
}

func (r *changeTrackingRepository) GetChangedLocationsSinceMainHash(mainHashID int64, limit, offset int) ([]models.Location, int64, error) {
	changedIDs, err := r.GetChangedIDsSinceMainHash(mainHashID, "location")
	if err != nil {
		return nil, 0, err
	}

	if len(changedIDs) == 0 {
		return []models.Location{}, 0, nil
	}

	var locations []models.Location
	var total int64

	// Get total count
	err = r.db.Model(&models.Location{}).Where("id IN ?", changedIDs).Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	// Get paginated results
	err = r.db.Where("id IN ?", changedIDs).
		Order("id ASC").
		Limit(limit).
		Offset(offset).
		Find(&locations).Error

	return locations, total, err
}
