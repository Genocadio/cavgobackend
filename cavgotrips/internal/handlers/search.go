package handlers

import (
	"context"
	"net/http"
	"time"

	"cavgotrips/internal/search"
	"cavgotrips/pkg/utils"
)

// SearchHandler exposes admin controls for the Meilisearch-backed search.
type SearchHandler struct {
	manager *search.Manager
}

func NewSearchHandler(manager *search.Manager) *SearchHandler {
	return &SearchHandler{manager: manager}
}

// Reindex rebuilds one (or all) search indexes on demand:
// POST /admin/search/reindex?entity=locations|routes|trips|all
func (h *SearchHandler) Reindex(w http.ResponseWriter, r *http.Request) {
	if h.manager == nil {
		utils.ErrorResponse(w, "search is not configured", http.StatusServiceUnavailable)
		return
	}

	entity := r.URL.Query().Get("entity")
	if entity == "" {
		entity = "all"
	}
	switch entity {
	case "locations", "routes", "trips", "all":
	default:
		utils.ErrorResponse(w, "invalid entity; expected locations, routes, trips, or all", http.StatusBadRequest)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Minute)
	defer cancel()

	if err := h.manager.Reindex(ctx, entity); err != nil {
		utils.ErrorResponse(w, "reindex failed: "+err.Error(), http.StatusInternalServerError)
		return
	}

	utils.JSONResponse(w, map[string]any{
		"message":  "reindex complete",
		"entity":   entity,
		"provider": h.manager.ActiveProvider(),
	}, http.StatusOK)
}
