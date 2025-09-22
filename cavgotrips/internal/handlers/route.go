package handlers

import (
	"cavgotrips/internal/models"
	"cavgotrips/internal/service"
	"cavgotrips/pkg/utils"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"

	"github.com/gorilla/mux"
)

type RouteHandler struct {
	service *service.RouteService
}

func NewRouteHandler(service *service.RouteService) *RouteHandler {
	return &RouteHandler{service: service}
}

func (h *RouteHandler) CreateRoute(w http.ResponseWriter, r *http.Request) {
	var route models.Route
	if err := json.NewDecoder(r.Body).Decode(&route); err != nil {
		utils.ErrorResponse(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	if err := h.service.CreateRoute(&route); err != nil {
		var validationErr *models.ValidationError
		var conflictErr *models.ConflictError

		if errors.As(err, &validationErr) {
			utils.ErrorResponse(w, err.Error(), http.StatusBadRequest)
			return
		} else if errors.As(err, &conflictErr) {
			utils.ErrorResponse(w, err.Error(), http.StatusConflict)
			return
		}

		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// Get the complete route with relationships
	completeRoute, err := h.service.GetRouteByID(route.ID)
	if err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.JSONResponse(w, completeRoute, http.StatusCreated)
}

func (h *RouteHandler) GetRoute(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id, err := strconv.ParseInt(vars["id"], 10, 64)
	if err != nil {
		utils.ErrorResponse(w, "Invalid route ID", http.StatusBadRequest)
		return
	}

	route, err := h.service.GetRouteByID(id)
	if err != nil {
		utils.ErrorResponse(w, "Route not found", http.StatusNotFound)
		return
	}

	utils.JSONResponse(w, route, http.StatusOK)
}

func (h *RouteHandler) GetRoutes(w http.ResponseWriter, r *http.Request) {
	// Parse query parameters
	queryParams := r.URL.Query()

	// Parse pagination parameters with defaults
	page, _ := strconv.Atoi(queryParams.Get("page"))
	if page <= 0 {
		page = 1
	}

	limit, _ := strconv.Atoi(queryParams.Get("limit"))
	if limit <= 0 {
		limit = 20 // Default limit
	}
	if limit > 100 {
		limit = 100 // Maximum limit
	}

	// Calculate offset
	offset := (page - 1) * limit

	// Parse filter parameters
	origin := queryParams.Get("origin")
	destination := queryParams.Get("destination")
	cityRouteStr := queryParams.Get("city_route")
	originProvince := queryParams.Get("origin_province")
	destinationProvince := queryParams.Get("destination_province")

	// Parse city_route parameter
	var cityRoute *bool
	if cityRouteStr != "" {
		cityRouteBool, err := strconv.ParseBool(cityRouteStr)
		if err != nil {
			utils.ErrorResponse(w, "Invalid city_route parameter; must be 'true' or 'false'", http.StatusBadRequest)
			return
		}
		cityRoute = &cityRouteBool
	}

	var routes []models.Route
	var total int64
	var err error

	// Determine which service method to call based on provided filters
	hasFilters := origin != "" || destination != "" || cityRoute != nil || originProvince != "" || destinationProvince != ""

	if hasFilters {
		// Use search and filter with pagination
		routes, total, err = h.service.SearchAndFilterPaginated(origin, destination, cityRoute, originProvince, destinationProvince, limit, offset)
	} else {
		// Use basic pagination
		routes, total, err = h.service.GetAllRoutesPaginated(limit, offset)
	}

	if err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.PaginatedJSONResponse(w, routes, total, page, limit, http.StatusOK)
}

func (h *RouteHandler) DeleteRoute(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id, err := strconv.ParseInt(vars["id"], 10, 64)
	if err != nil {
		utils.ErrorResponse(w, "Invalid route ID", http.StatusBadRequest)
		return
	}

	if err := h.service.DeleteRoute(id); err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.JSONResponse(w, map[string]string{"message": "Route deleted successfully"}, http.StatusOK)
}

func (h *RouteHandler) UpdateRoute(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id, err := strconv.ParseInt(vars["id"], 10, 64)
	if err != nil {
		utils.ErrorResponse(w, "Invalid route ID", http.StatusBadRequest)
		return
	}

	var route models.Route
	if err := json.NewDecoder(r.Body).Decode(&route); err != nil {
		utils.ErrorResponse(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	// Set the ID to ensure we're updating the correct route
	route.ID = id

	if err := h.service.UpdateRoute(&route); err != nil {
		var validationErr *models.ValidationError
		var conflictErr *models.ConflictError

		if errors.As(err, &validationErr) {
			utils.ErrorResponse(w, err.Error(), http.StatusBadRequest)
			return
		} else if errors.As(err, &conflictErr) {
			utils.ErrorResponse(w, err.Error(), http.StatusConflict)
			return
		}

		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// Get the complete updated route with relationships
	completeRoute, err := h.service.GetRouteByID(id)
	if err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.JSONResponse(w, completeRoute, http.StatusOK)
}

func (h *RouteHandler) GetRoutesByPriceRange(w http.ResponseWriter, r *http.Request) {
	// Parse query parameters
	queryParams := r.URL.Query()
	minPriceStr := queryParams.Get("min_price")
	maxPriceStr := queryParams.Get("max_price")

	// Parse min price
	var minPrice float64
	if minPriceStr != "" {
		var err error
		minPrice, err = strconv.ParseFloat(minPriceStr, 64)
		if err != nil {
			utils.ErrorResponse(w, "Invalid min_price parameter", http.StatusBadRequest)
			return
		}
	}

	// Parse max price
	var maxPrice float64
	if maxPriceStr != "" {
		var err error
		maxPrice, err = strconv.ParseFloat(maxPriceStr, 64)
		if err != nil {
			utils.ErrorResponse(w, "Invalid max_price parameter", http.StatusBadRequest)
			return
		}
	}

	// Validate price range
	if minPrice > 0 && maxPrice > 0 && minPrice > maxPrice {
		utils.ErrorResponse(w, "min_price cannot be greater than max_price", http.StatusBadRequest)
		return
	}

	routes, err := h.service.GetRoutesByPriceRange(minPrice, maxPrice)
	if err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.JSONResponse(w, routes, http.StatusOK)
}

func (h *RouteHandler) GetRoutesByDistanceRange(w http.ResponseWriter, r *http.Request) {
	// Parse query parameters
	queryParams := r.URL.Query()
	minDistanceStr := queryParams.Get("min_distance")
	maxDistanceStr := queryParams.Get("max_distance")

	// Parse min distance
	var minDistance int
	if minDistanceStr != "" {
		var err error
		minDistance, err = strconv.Atoi(minDistanceStr)
		if err != nil {
			utils.ErrorResponse(w, "Invalid min_distance parameter", http.StatusBadRequest)
			return
		}
	}

	// Parse max distance
	var maxDistance int
	if maxDistanceStr != "" {
		var err error
		maxDistance, err = strconv.Atoi(maxDistanceStr)
		if err != nil {
			utils.ErrorResponse(w, "Invalid max_distance parameter", http.StatusBadRequest)
			return
		}
	}

	// Validate distance range
	if minDistance > 0 && maxDistance > 0 && minDistance > maxDistance {
		utils.ErrorResponse(w, "min_distance cannot be greater than max_distance", http.StatusBadRequest)
		return
	}

	routes, err := h.service.GetRoutesByDistanceRange(minDistance, maxDistance)
	if err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.JSONResponse(w, routes, http.StatusOK)
}

func (h *RouteHandler) GetRouteStatistics(w http.ResponseWriter, r *http.Request) {
	statistics, err := h.service.GetRouteStatistics()
	if err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.JSONResponse(w, statistics, http.StatusOK)
}
