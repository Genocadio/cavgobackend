package models

import (
	"errors"
	"time"
)

// Location represents a geographical location
type Location struct {
	ID              int64     `json:"id" gorm:"primaryKey"`
	Latitude        float64   `json:"latitude" gorm:"not null"`
	Code            *string   `json:"code"`
	Longitude       float64   `json:"longitude" gorm:"not null"`
	GooglePlaceName *string   `json:"google_place_name"`
	CustomName      *string   `json:"custom_name"`
	Province        *string   `json:"province"`
	District        *string   `json:"district"`
	PlaceID         *string   `json:"place_id"`
	CreatedAt       time.Time `json:"created_at"`
	UpdatedAt       time.Time `json:"updated_at"`
}

func (l *Location) Validate() error {
	if l.Latitude == 0 || l.Longitude == 0 {
		return errors.New("latitude and longitude are required and cannot be zero")
	}

	if l.CustomName == nil && l.GooglePlaceName == nil {
		return errors.New("either CustomName or GooglePlaceName must be provided")
	}

	// For automatic code generation, province and district are required
	if l.Province == nil || l.District == nil {
		return errors.New("province and district are required for location code generation")
	}

	return nil
}
