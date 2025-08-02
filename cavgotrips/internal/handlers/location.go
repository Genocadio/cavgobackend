package handlers

import (
	"cavgotrips/internal/models"
	"cavgotrips/internal/service"
	"cavgotrips/pkg/utils"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"

	"github.com/gorilla/mux"
	"gorm.io/gorm"
)

type LocationHandler struct {
	service *service.LocationService
}

func NewLocationHandler(service *service.LocationService) *LocationHandler {
	return &LocationHandler{service: service}
}

func (h *LocationHandler) CreateLocation(w http.ResponseWriter, r *http.Request) {
	var location models.Location
	if err := json.NewDecoder(r.Body).Decode(&location); err != nil {
		utils.ErrorResponse(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	if err := h.service.CreateLocation(&location); err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.JSONResponse(w, location, http.StatusCreated)
}

func (h *LocationHandler) GetLocations(w http.ResponseWriter, r *http.Request) {
	// Parse query parameters
	queryParams := r.URL.Query()
	searchTerm := queryParams.Get("search")
	
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
	
	// If search term is provided, use search functionality
	if searchTerm != "" {
		// URL decode the search term
		decodedSearchTerm, err := url.QueryUnescape(searchTerm)
		if err != nil {
			utils.ErrorResponse(w, "Invalid search parameter", http.StatusBadRequest)
			return
		}
		
		locations, total, err := h.service.SearchLocationsPaginated(decodedSearchTerm, limit, offset)
		if err != nil {
			utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
			return
		}
		
		utils.PaginatedJSONResponse(w, locations, total, page, limit, http.StatusOK)
		return
	}
	
	// If no search term, return paginated locations
	locations, total, err := h.service.GetAllLocationsPaginated(limit, offset)
	if err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.PaginatedJSONResponse(w, locations, total, page, limit, http.StatusOK)
}

func (h *LocationHandler) GetLocation(w http.ResponseWriter, r *http.Request) {
	// Extract ID from URL parameters
	vars := mux.Vars(r)
	idStr := vars["id"]
	
	id, err := strconv.ParseInt(idStr, 10, 64)
	if err != nil {
		utils.ErrorResponse(w, "Invalid location ID", http.StatusBadRequest)
		return
	}

	location, err := h.service.GetLocationByID(id)
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			utils.ErrorResponse(w, "Location not found", http.StatusNotFound)
			return
		}
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.JSONResponse(w, location, http.StatusOK)
}

func (h *LocationHandler) UpdateLocation(w http.ResponseWriter, r *http.Request) {
	// Extract ID from URL parameters
	vars := mux.Vars(r)
	idStr := vars["id"]
	
	id, err := strconv.ParseInt(idStr, 10, 64)
	if err != nil {
		utils.ErrorResponse(w, "Invalid location ID", http.StatusBadRequest)
		return
	}

	var location models.Location
	if err := json.NewDecoder(r.Body).Decode(&location); err != nil {
		utils.ErrorResponse(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	if err := h.service.UpdateLocation(id, &location); err != nil {
		if err == gorm.ErrRecordNotFound {
			utils.ErrorResponse(w, "Location not found", http.StatusNotFound)
			return
		}
		if _, ok := err.(*models.ValidationError); ok {
			utils.ErrorResponse(w, err.Error(), http.StatusBadRequest)
			return
		}
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.JSONResponse(w, location, http.StatusOK)
}

func (h *LocationHandler) DeleteLocation(w http.ResponseWriter, r *http.Request) {
	// Extract ID from URL parameters
	vars := mux.Vars(r)
	idStr := vars["id"]
	
	id, err := strconv.ParseInt(idStr, 10, 64)
	if err != nil {
		utils.ErrorResponse(w, "Invalid location ID", http.StatusBadRequest)
		return
	}

	if err := h.service.DeleteLocation(id); err != nil {
		if err == gorm.ErrRecordNotFound {
			utils.ErrorResponse(w, "Location not found", http.StatusNotFound)
			return
		}
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.JSONResponse(w, map[string]string{"message": "Location deleted successfully"}, http.StatusOK)
}
