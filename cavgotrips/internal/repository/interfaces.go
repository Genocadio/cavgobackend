package repository

import "cavgotrips/internal/models"

type LocationRepository interface {
	Create(location *models.Location) error
	GetAll() ([]models.Location, error)
	GetAllPaginated(limit, offset int) ([]models.Location, int64, error)
	GetByID(id int64) (*models.Location, error)
	Search(searchTerm string) ([]models.Location, error)
	SearchPaginated(searchTerm string, limit, offset int) ([]models.Location, int64, error)
	ExistsByCustomName(customName string) (bool, error)
	ExistsByPlaceID(placeID string) (bool, error)
	ExistsByCode(code string) (bool, error)
	ExistsByLatLng(lat, lng float64) (bool, error)
	ValidateExists(id int64) error
	GenerateLocationCode(province, district string) (string, error)
	Update(location *models.Location) error
	Delete(id int64) error
}

type RouteRepository interface {
	Create(route *models.Route) error
	GetAll() ([]models.Route, error)
	GetAllPaginated(limit, offset int) ([]models.Route, int64, error)
	GetByID(id int64) (*models.Route, error)
	GetByIDWithWaypoints(id int64) (*models.Route, error)
	Update(route *models.Route) error
	Delete(id int64) error
	CheckUniqueness(route *models.Route) error
	SearchByOriginDestination(origin, destination string) ([]models.Route, error)
	SearchByOriginDestinationPaginated(origin, destination string, limit, offset int) ([]models.Route, int64, error)
	FilterByCityRoute(cityRoute *bool) ([]models.Route, error)
	FilterByCityRoutePaginated(cityRoute *bool, limit, offset int) ([]models.Route, int64, error)
	FilterByProvinces(originProvince, destinationProvince string) ([]models.Route, error)
	FilterByProvincesPaginated(originProvince, destinationProvince string, limit, offset int) ([]models.Route, int64, error)
	SearchAndFilter(origin, destination string, cityRoute *bool, originProvince, destinationProvince string) ([]models.Route, error)
	SearchAndFilterPaginated(origin, destination string, cityRoute *bool, originProvince, destinationProvince string, limit, offset int) ([]models.Route, int64, error)
	GetRoutesByPriceRange(minPrice, maxPrice float64) ([]models.Route, error)
	GetRoutesByPriceRangePaginated(minPrice, maxPrice float64, limit, offset int) ([]models.Route, int64, error)
	GetRoutesByDistanceRange(minDistance, maxDistance int) ([]models.Route, error)
	GetRoutesByDistanceRangePaginated(minDistance, maxDistance int, limit, offset int) ([]models.Route, int64, error)
	GetRouteStatistics() (map[string]interface{}, error)
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
	Delete(id int64) error
}
