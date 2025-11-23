package database

import (
	"cavgotrips/internal/models"

	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

func Initialize(databaseURL string) (*gorm.DB, error) {
	db, err := gorm.Open(postgres.Open(databaseURL), &gorm.Config{})
	if err != nil {
		return nil, err
	}

	// Auto migrate the schema
	err = db.AutoMigrate(
		&models.Location{},
		&models.Route{},
		&models.RouteWaypoint{},
		&models.Trip{},
		&models.TripWaypoint{},
		&models.SSESession{},
		&models.ChangeBatch{},
		&models.Change{},
		&models.MainHash{},
		&models.TripLog{},
		&models.TripWaypointLog{},
	)
	if err != nil {
		return nil, err
	}

	return db, nil
}
