package handlers

import (
	"cavgotrips/internal/models"
	"cavgotrips/internal/service"
	"cavgotrips/pkg/utils"
	"log"
	"net/http"
	"strconv"
)

type SyncHandler struct {
	changeTrackingService *service.ChangeTrackingService
	routeService          *service.RouteService
	locationService       *service.LocationService
}

func NewSyncHandler(changeTrackingService *service.ChangeTrackingService, routeService *service.RouteService, locationService *service.LocationService) *SyncHandler {
	return &SyncHandler{
		changeTrackingService: changeTrackingService,
		routeService:          routeService,
		locationService:       locationService,
	}
}

// GetMainHash returns the latest main hash
func (h *SyncHandler) GetMainHash(w http.ResponseWriter, r *http.Request) {
	mainHash, err := h.changeTrackingService.GetLatestMainHash()
	if err != nil {
		utils.ErrorResponse(w, "Failed to get main hash: "+err.Error(), http.StatusInternalServerError)
		return
	}

	if mainHash == nil {
		utils.ErrorResponse(w, "No main hash found", http.StatusNotFound)
		return
	}

	utils.JSONResponse(w, mainHash, http.StatusOK)
}

// TriggerMerge manually triggers merge operation
func (h *SyncHandler) TriggerMerge(w http.ResponseWriter, r *http.Request) {
	err := h.changeTrackingService.MergeBatches()
	if err != nil {
		utils.ErrorResponse(w, "Failed to merge batches: "+err.Error(), http.StatusInternalServerError)
		return
	}

	utils.JSONResponse(w, map[string]string{"message": "Merge completed successfully"}, http.StatusOK)
}

// GetUnmergedBatches returns unmerged batches for debugging
func (h *SyncHandler) GetUnmergedBatches(w http.ResponseWriter, r *http.Request) {
	// This would require exposing GetUnmergedBatches from service
	// For now, return a placeholder
	utils.JSONResponse(w, map[string]string{"message": "Endpoint not yet implemented"}, http.StatusNotImplemented)
}

// SyncRoutesByHash handles hash-based route sync with pagination
// If hash is not provided, returns all routes with latest hash
func (h *SyncHandler) SyncRoutesByHash(w http.ResponseWriter, r *http.Request) {
	hash := r.URL.Query().Get("hash")

	// Parse pagination parameters
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page <= 0 {
		page = 1
	}

	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 {
		limit = 20
	}
	if limit > 100 {
		limit = 100
	}

	offset := (page - 1) * limit

	// Get current main hash
	currentMainHash, err := h.changeTrackingService.GetLatestMainHash()
	if err != nil {
		utils.ErrorResponse(w, "Failed to get main hash: "+err.Error(), http.StatusInternalServerError)
		return
	}

	// If no hash provided, return all routes (works even if no main hash exists yet)
	if hash == "" {
		allRoutes, total, err := h.routeService.GetAllRoutesPaginated(limit, offset)
		if err != nil {
			log.Printf("[SyncHandler] Error getting all routes: %v", err)
			utils.ErrorResponse(w, "Failed to get routes: "+err.Error(), http.StatusInternalServerError)
			return
		}

		// Clear waypoints to return minimal data (location_id refs only)
		for i := range allRoutes {
			allRoutes[i].Waypoints = nil
		}

		// Use current hash if available, otherwise use empty string to indicate no hash yet
		hashValue := ""
		if currentMainHash != nil {
			hashValue = currentMainHash.Hash
		}

		response := models.RoutesSyncResponse{
			Hash:       hashValue,
			Changed:    true, // Always true when no hash provided (initial sync)
			Routes:     allRoutes,
			Changes:    []models.RouteSyncChange{},
			DeletedIDs: []int64{},
			Page:       page,
			Limit:      limit,
			Total:      total,
		}

		utils.JSONResponse(w, response, http.StatusOK)
		return
	}

	// Hash provided - need main hash to compare
	if currentMainHash == nil {
		// No main hash exists yet, but client provided a hash
		// Return all routes with empty hash (indicates no hash tracking yet)
		allRoutes, total, err := h.routeService.GetAllRoutesPaginated(limit, offset)
		if err != nil {
			log.Printf("[SyncHandler] Error getting all routes: %v", err)
			utils.ErrorResponse(w, "Failed to get routes: "+err.Error(), http.StatusInternalServerError)
			return
		}

		// Clear waypoints to return minimal data
		for i := range allRoutes {
			allRoutes[i].Waypoints = nil
		}

		response := models.RoutesSyncResponse{
			Hash:       "",
			Changed:    true,
			Routes:     allRoutes,
			Changes:    []models.RouteSyncChange{},
			DeletedIDs: []int64{},
			Page:       page,
			Limit:      limit,
			Total:      total,
			Message:    "No main hash exists on server yet. Returning all routes.",
		}
		utils.JSONResponse(w, response, http.StatusOK)
		return
	}

	// Hash provided - compare with current main hash
	matches, clientMainHash, _ := h.changeTrackingService.CompareHash(hash)

	// Check if client hash is invalid (not found in database)
	if clientMainHash == nil {
		// Return empty data with message (not an error) - allows client to handle gracefully
		response := models.RoutesSyncResponse{
			Hash:       currentMainHash.Hash,
			Changed:    false,
			Routes:     []models.Route{},
			Changes:    []models.RouteSyncChange{},
			DeletedIDs: []int64{},
			Message:    "Invalid hash: hash not found in database. Please sync without hash parameter to get all data.",
		}
		utils.JSONResponse(w, response, http.StatusOK)
		return
	}

	// If hash matches, check if there are changes since this hash was created
	// (e.g., unmerged changes that haven't been merged into a new hash yet)
	if matches {
		// Check for changes since this hash was created
		changes, routes, deletedIDs, total, err := h.changeTrackingService.GetRouteSyncChangesSinceHash(hash, limit, offset)
		if err != nil {
			log.Printf("[SyncHandler] Error getting changed routes: %v", err)
			// If error, return unchanged response
			response := models.RoutesSyncResponse{
				Hash:       currentMainHash.Hash,
				Changed:    false,
				Routes:     []models.Route{},
				Changes:    []models.RouteSyncChange{},
				DeletedIDs: []int64{},
			}
			utils.JSONResponse(w, response, http.StatusOK)
			return
		}

		// If there are changes, return them even though hash matches
		if total > 0 || len(deletedIDs) > 0 {
			response := models.RoutesSyncResponse{
				Hash:       currentMainHash.Hash, // Hash hasn't changed yet (unmerged changes)
				Changed:    true,
				Routes:     routes,
				Changes:    changes,
				DeletedIDs: deletedIDs,
				Page:       page,
				Limit:      limit,
				Total:      total,
			}
			utils.JSONResponse(w, response, http.StatusOK)
			return
		}

		// No changes found
		response := models.RoutesSyncResponse{
			Hash:       currentMainHash.Hash,
			Changed:    false,
			Routes:     []models.Route{},
			Changes:    []models.RouteSyncChange{},
			DeletedIDs: []int64{},
		}
		utils.JSONResponse(w, response, http.StatusOK)
		return
	}

	// Hash doesn't match, get changed routes
	changes, routes, deletedIDs, total, err := h.changeTrackingService.GetRouteSyncChangesSinceHash(hash, limit, offset)
	if err != nil {
		log.Printf("[SyncHandler] Error getting changed routes: %v", err)
		utils.ErrorResponse(w, "Failed to get changed routes: "+err.Error(), http.StatusInternalServerError)
		return
	}

	response := models.RoutesSyncResponse{
		Hash:       currentMainHash.Hash,
		Changed:    true,
		Routes:     routes,
		Changes:    changes,
		DeletedIDs: deletedIDs,
		Page:       page,
		Limit:      limit,
		Total:      total,
	}

	utils.JSONResponse(w, response, http.StatusOK)
}

// SyncLocationsByHash handles hash-based location sync with pagination
// If hash is not provided, returns all locations with latest hash
func (h *SyncHandler) SyncLocationsByHash(w http.ResponseWriter, r *http.Request) {
	hash := r.URL.Query().Get("hash")

	// Parse pagination parameters
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page <= 0 {
		page = 1
	}

	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 {
		limit = 20
	}
	if limit > 100 {
		limit = 100
	}

	offset := (page - 1) * limit

	// Get current main hash
	currentMainHash, err := h.changeTrackingService.GetLatestMainHash()
	if err != nil {
		utils.ErrorResponse(w, "Failed to get main hash: "+err.Error(), http.StatusInternalServerError)
		return
	}

	// If no hash provided, return all locations (works even if no main hash exists yet)
	if hash == "" {
		allLocations, total, err := h.locationService.GetAllLocationsPaginated(limit, offset)
		if err != nil {
			log.Printf("[SyncHandler] Error getting all locations: %v", err)
			utils.ErrorResponse(w, "Failed to get locations: "+err.Error(), http.StatusInternalServerError)
			return
		}

		// Use current hash if available, otherwise use empty string to indicate no hash yet
		hashValue := ""
		if currentMainHash != nil {
			hashValue = currentMainHash.Hash
		}

		response := models.LocationsSyncResponse{
			Hash:       hashValue,
			Changed:    true, // Always true when no hash provided (initial sync)
			Locations:  allLocations,
			Changes:    []models.LocationSyncChange{},
			DeletedIDs: []int64{},
			Page:       page,
			Limit:      limit,
			Total:      total,
		}

		utils.JSONResponse(w, response, http.StatusOK)
		return
	}

	// Hash provided - need main hash to compare
	if currentMainHash == nil {
		// No main hash exists yet, but client provided a hash
		// Return all locations with empty hash (indicates no hash tracking yet)
		allLocations, total, err := h.locationService.GetAllLocationsPaginated(limit, offset)
		if err != nil {
			log.Printf("[SyncHandler] Error getting all locations: %v", err)
			utils.ErrorResponse(w, "Failed to get locations: "+err.Error(), http.StatusInternalServerError)
			return
		}

		response := models.LocationsSyncResponse{
			Hash:       "",
			Changed:    true,
			Locations:  allLocations,
			Changes:    []models.LocationSyncChange{},
			DeletedIDs: []int64{},
			Page:       page,
			Limit:      limit,
			Total:      total,
			Message:    "No main hash exists on server yet. Returning all locations.",
		}
		utils.JSONResponse(w, response, http.StatusOK)
		return
	}

	// Hash provided - compare with current main hash
	matches, clientMainHash, _ := h.changeTrackingService.CompareHash(hash)

	// Check if client hash is invalid (not found in database)
	if clientMainHash == nil {
		// Return empty data with message (not an error) - allows client to handle gracefully
		response := models.LocationsSyncResponse{
			Hash:       currentMainHash.Hash,
			Changed:    false,
			Locations:  []models.Location{},
			Changes:    []models.LocationSyncChange{},
			DeletedIDs: []int64{},
			Message:    "Invalid hash: hash not found in database. Please sync without hash parameter to get all data.",
		}
		utils.JSONResponse(w, response, http.StatusOK)
		return
	}

	// If hash matches, check if there are changes since this hash was created
	// (e.g., unmerged changes that haven't been merged into a new hash yet)
	if matches {
		// Check for changes since this hash was created
		changes, locations, deletedIDs, total, err := h.changeTrackingService.GetLocationSyncChangesSinceHash(hash, limit, offset)
		if err != nil {
			log.Printf("[SyncHandler] Error getting changed locations: %v", err)
			// If error, return unchanged response
			response := models.LocationsSyncResponse{
				Hash:       currentMainHash.Hash,
				Changed:    false,
				Locations:  []models.Location{},
				Changes:    []models.LocationSyncChange{},
				DeletedIDs: []int64{},
			}
			utils.JSONResponse(w, response, http.StatusOK)
			return
		}

		// If there are changes, return them even though hash matches
		if total > 0 || len(deletedIDs) > 0 {
			response := models.LocationsSyncResponse{
				Hash:       currentMainHash.Hash, // Hash hasn't changed yet (unmerged changes)
				Changed:    true,
				Locations:  locations,
				Changes:    changes,
				DeletedIDs: deletedIDs,
				Page:       page,
				Limit:      limit,
				Total:      total,
			}
			utils.JSONResponse(w, response, http.StatusOK)
			return
		}

		// No changes found
		response := models.LocationsSyncResponse{
			Hash:       currentMainHash.Hash,
			Changed:    false,
			Locations:  []models.Location{},
			Changes:    []models.LocationSyncChange{},
			DeletedIDs: []int64{},
		}
		utils.JSONResponse(w, response, http.StatusOK)
		return
	}

	// Hash doesn't match, get changed locations
	changes, locations, deletedIDs, total, err := h.changeTrackingService.GetLocationSyncChangesSinceHash(hash, limit, offset)
	if err != nil {
		log.Printf("[SyncHandler] Error getting changed locations: %v", err)
		utils.ErrorResponse(w, "Failed to get changed locations: "+err.Error(), http.StatusInternalServerError)
		return
	}

	response := models.LocationsSyncResponse{
		Hash:       currentMainHash.Hash,
		Changed:    true,
		Locations:  locations,
		Changes:    changes,
		DeletedIDs: deletedIDs,
		Page:       page,
		Limit:      limit,
		Total:      total,
	}

	utils.JSONResponse(w, response, http.StatusOK)
}
