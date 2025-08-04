package models

import (
	"errors"
	"log"
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
	log.Printf("DEBUG: Model: Validating location - ID: %d, Lat: %f, Lng: %f, Province: %v, District: %v, CustomName: %v, GooglePlaceName: %v", 
		l.ID, l.Latitude, l.Longitude, l.Province, l.District, l.CustomName, l.GooglePlaceName)

	if l.Latitude == 0 || l.Longitude == 0 {
		log.Printf("ERROR: Model: Validation failed - latitude or longitude is zero - Lat: %f, Lng: %f", l.Latitude, l.Longitude)
		return errors.New("latitude and longitude are required and cannot be zero")
	}

	if l.CustomName == nil && l.GooglePlaceName == nil {
		log.Printf("ERROR: Model: Validation failed - both CustomName and GooglePlaceName are nil")
		return errors.New("either CustomName or GooglePlaceName must be provided")
	}

	// For automatic code generation, province and district are required
	if l.Province == nil || l.District == nil {
		log.Printf("ERROR: Model: Validation failed - province or district is nil - Province: %v, District: %v", l.Province, l.District)
		return errors.New("province and district are required for location code generation")
	}

	log.Printf("DEBUG: Model: Location validation passed successfully")
	return nil
}
