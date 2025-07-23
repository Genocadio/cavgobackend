package repository

import "cavgotrips/internal/models"

type LocationRepository interface {
	Create(location *models.Location) error
	GetAll() ([]models.Location, error)
	GetByID(id int64) (*models.Location, error)
	ExistsByCustomName(customName string) (bool, error)
	ExistsByPlaceID(placeID string) (bool, error)
	ExistsByCode(code string) (bool, error)
	ExistsByLatLng(lat, lng float64) (bool, error)
	ValidateExists(id int64) error
}

type RouteRepository interface {
	Create(route *models.Route) error
	GetAll() ([]models.Route, error)
	GetByID(id int64) (*models.Route, error)
	GetByIDWithWaypoints(id int64) (*models.Route, error)
	CheckUniqueness(route *models.Route) error
}

type TripRepository interface {
	Create(trip *models.Trip) error
	CreateWaypoint(waypoint *models.TripWaypoint) error
	GetAll() ([]models.Trip, error)
	GetByID(id int64) (*models.Trip, error)
	GetByIDWithRelations(id int64) (*models.Trip, error)
	UpdateProgress(id int64, updates map[string]interface{}) error
	UpdateWaypointProgress(waypointID int64, updates map[string]interface{}) error
	MarkWaypointPassed(waypointID int64, timestamp int64) error
	GetTripsByStatus(status string) ([]models.Trip, error)
	GetTripsByCarPlate(carPlate string) ([]models.Trip, error)
	GetTripsByFilters(origin, destination, company string) ([]models.Trip, error)
	GetTripsByFiltersPaginated(origin, destination, company string, limit, offset int) ([]models.Trip, int64, error)
	GetTripsByVehicleID(vehicleID int64) ([]models.Trip, error)
	GetTripsByCityRoute(cityRoute bool) ([]models.Trip, error)
	GetTripsByFiltersWithCityRoute(origin, destination, company string, cityRoute bool, limit, offset int) ([]models.Trip, int64, error)
}
