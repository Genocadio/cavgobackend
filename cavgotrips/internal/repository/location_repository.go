package repository

import (
	"cavgotrips/internal/models"

	"gorm.io/gorm"
)

type locationRepository struct {
	db *gorm.DB
}

func NewLocationRepository(db *gorm.DB) LocationRepository {
	return &locationRepository{db: db}
}

func (r *locationRepository) Create(location *models.Location) error {
	return r.db.Create(location).Error
}

func (r *locationRepository) GetAll() ([]models.Location, error) {
	var locations []models.Location
	err := r.db.Find(&locations).Error
	return locations, err
}

func (r *locationRepository) ExistsByCustomName(customName string) (bool, error) {
	var count int64
	err := r.db.Model(&models.Location{}).
		Where("custom_name = ?", customName).
		Count(&count).Error
	return count > 0, err
}

func (r *locationRepository) ExistsByCode(code string) (bool, error) {
	var count int64
	err := r.db.Model(&models.Location{}).
		Where("code =?", code).
		Count(&count).Error
	return count > 0, err
}

func (r *locationRepository) ExistsByPlaceID(placeID string) (bool, error) {
	var count int64
	err := r.db.Model(&models.Location{}).
		Where("place_id = ?", placeID).
		Count(&count).Error
	return count > 0, err
}

func (r *locationRepository) ExistsByLatLng(lat, lng float64) (bool, error) {
	var count int64
	err := r.db.Model(&models.Location{}).
		Where("latitude = ? AND longitude = ?", lat, lng).
		Count(&count).Error
	return count > 0, err
}

func (r *locationRepository) GetByID(id int64) (*models.Location, error) {
	var location models.Location
	err := r.db.First(&location, id).Error
	if err != nil {
		return nil, err
	}
	return &location, nil
}

func (r *locationRepository) ValidateExists(id int64) error {
	var count int64
	err := r.db.Model(&models.Location{}).
		Where("id = ?", id).
		Count(&count).Error
	if err != nil {
		return err
	}
	if count == 0 {
		return gorm.ErrRecordNotFound
	}
	return nil
}
