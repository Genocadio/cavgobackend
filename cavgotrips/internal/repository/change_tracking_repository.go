package repository

import (
	"cavgotrips/internal/models"
	"gorm.io/gorm"
)

type ChangeTrackingRepository interface {
	CreateChangeBatch() (*models.ChangeBatch, error)
	CreateChange(batchID int64, changedType string, changedID int64, isDeleted bool) error
	GetUnmergedBatches() ([]models.ChangeBatch, error)
	GetChangesByBatchID(batchID int64) ([]models.Change, error)
	MarkBatchesAsMerged(batchIDs []int64) error
	GetLatestMainHash() (*models.MainHash, error)
	GetMainHashByHash(hash string) (*models.MainHash, error)
	CreateMainHash(mainHash *models.MainHash) error
	GetChangesSinceMainHash(mainHashID int64, changedType string) ([]models.Change, error)
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

func (r *changeTrackingRepository) CreateChange(batchID int64, changedType string, changedID int64, isDeleted bool) error {
	change := &models.Change{
		ChangeBatchID: batchID,
		ChangedType:   changedType,
		ChangedID:     changedID,
		IsDeleted:     isDeleted,
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
	// Get changes that happened after the hash was created
	// AND are in unmerged batches (batches that haven't been merged into a new hash yet)
	// This ensures we only return changes that haven't been included in a subsequent merge
	err := r.db.Joins("JOIN change_batches ON changes.change_batch_id = change_batches.id").
		Where("changes.changed_type = ? AND changes.created_at > ? AND change_batches.merged = ?", 
			changedType, mainHash.CreatedAt, false).
		Order("changes.created_at ASC").
		Select("changes.*").
		Find(&changes).Error
	return changes, err
}

func (r *changeTrackingRepository) GetChangedIDsSinceMainHash(mainHashID int64, changedType string) ([]int64, error) {
	changes, err := r.GetChangesSinceMainHash(mainHashID, changedType)
	if err != nil {
		return nil, err
	}

	// Get unique IDs
	idMap := make(map[int64]bool)
	for _, change := range changes {
		idMap[change.ChangedID] = true
	}

	ids := make([]int64, 0, len(idMap))
	for id := range idMap {
		ids = append(ids, id)
	}

	return ids, nil
}

func (r *changeTrackingRepository) GetDeletedIDsSinceMainHash(mainHashID int64, changedType string) ([]int64, error) {
	var mainHash models.MainHash
	if err := r.db.First(&mainHash, mainHashID).Error; err != nil {
		return nil, err
	}

	var changes []models.Change
	// Get deleted changes that happened after the hash was created
	// AND are in unmerged batches (batches that haven't been merged into a new hash yet)
	err := r.db.Joins("JOIN change_batches ON changes.change_batch_id = change_batches.id").
		Where("changes.changed_type = ? AND changes.is_deleted = ? AND changes.created_at > ? AND change_batches.merged = ?", 
			changedType, true, mainHash.CreatedAt, false).
		Select("changes.*").
		Find(&changes).Error
	if err != nil {
		return nil, err
	}

	ids := make([]int64, len(changes))
	for i, change := range changes {
		ids[i] = change.ChangedID
	}

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
		Limit(limit).
		Offset(offset).
		Find(&locations).Error

	return locations, total, err
}


