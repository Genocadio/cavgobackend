package repository

import (
	"cavgotrips/internal/models"
	"time"

	"gorm.io/gorm"
)

type tripLogRepository struct {
	db *gorm.DB
}

func NewTripLogRepository(db *gorm.DB) TripLogRepository {
	return &tripLogRepository{db: db}
}

func (r *tripLogRepository) Create(log *models.TripLog) error {
	return r.db.Create(log).Error
}

func (r *tripLogRepository) CreateWaypointLog(log *models.TripWaypointLog) error {
	return r.db.Create(log).Error
}

func (r *tripLogRepository) GetByTripID(tripID int64) ([]models.TripLog, error) {
	var logs []models.TripLog
	err := r.db.Where("trip_id = ?", tripID).
		Order("logged_at DESC").
		Find(&logs).Error
	return logs, err
}

func (r *tripLogRepository) DeleteLogsByDateRange(startDate, endDate time.Time) error {
	// Delete waypoint logs first (they reference trip logs via trip_log_id)
	// We need to delete waypoint logs for trips created in the date range
	// First, get all trip_log_ids for trips created in the date range
	var tripLogIDs []int64
	err := r.db.Model(&models.TripLog{}).
		Where("trip_created_at >= ? AND trip_created_at < ?", startDate, endDate).
		Pluck("id", &tripLogIDs).Error
	if err != nil {
		return err
	}

	// Delete waypoint logs associated with those trip logs
	if len(tripLogIDs) > 0 {
		err = r.db.Where("trip_log_id IN ?", tripLogIDs).
			Delete(&models.TripWaypointLog{}).Error
		if err != nil {
			return err
		}
	}

	// Delete trip logs for trips created in the date range
	err = r.db.Where("trip_created_at >= ? AND trip_created_at < ?", startDate, endDate).
		Delete(&models.TripLog{}).Error
	return err
}




