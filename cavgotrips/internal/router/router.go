package router

import (
	"cavgotrips/internal/handlers"
	"cavgotrips/internal/middleware"
	"encoding/json"
	"net/http"

	"github.com/gorilla/mux"
)

func Setup(
	locationHandler *handlers.LocationHandler,
	routeHandler *handlers.RouteHandler,
	tripHandler *handlers.TripHandler,
	sseHandler *handlers.SSEHandler,
	syncHandler *handlers.SyncHandler,
) *mux.Router {
	r := mux.NewRouter()

	// Add CORS middleware
	r.Use(middleware.CORSMiddleware)

	// Health check endpoint for Eureka
	r.HandleFunc("/health", healthCheck).Methods("GET")

	// Root endpoint for service discovery
	r.HandleFunc("/", rootHandler).Methods("GET")

	// Location endpoints
	r.HandleFunc("/locations", locationHandler.CreateLocation).Methods("POST")
	r.HandleFunc("/locations", locationHandler.GetLocations).Methods("GET")
	// Hash-based sync endpoint for locations - must be registered BEFORE /locations/{id} to avoid conflict
	r.HandleFunc("/locations/hash", syncHandler.SyncLocationsByHash).Methods("GET")
	// Register generic {id} routes AFTER specific routes to avoid path conflicts
	r.HandleFunc("/locations/{id}", locationHandler.GetLocation).Methods("GET")
	r.HandleFunc("/locations/{id}", locationHandler.UpdateLocation).Methods("PUT")
	r.HandleFunc("/locations/{id}", locationHandler.DeleteLocation).Methods("DELETE")

	// Route endpoints
	r.HandleFunc("/routes", routeHandler.CreateRoute).Methods("POST")
	r.HandleFunc("/routes", routeHandler.GetRoutes).Methods("GET")
	// Register specific routes BEFORE generic {id} routes to avoid path conflicts
	r.HandleFunc("/routes/price-range", routeHandler.GetRoutesByPriceRange).Methods("GET")
	r.HandleFunc("/routes/distance-range", routeHandler.GetRoutesByDistanceRange).Methods("GET")
	r.HandleFunc("/routes/statistics", routeHandler.GetRouteStatistics).Methods("GET")
	// Hash-based sync endpoint - must be BEFORE /routes/{id} to avoid "hash" being treated as ID
	r.HandleFunc("/routes/hash", syncHandler.SyncRoutesByHash).Methods("GET")
	r.HandleFunc("/routes/{id}", routeHandler.GetRoute).Methods("GET")
	r.HandleFunc("/routes/{id}", routeHandler.UpdateRoute).Methods("PUT")
	r.HandleFunc("/routes/{id}", routeHandler.DeleteRoute).Methods("DELETE")

	// Internal trip endpoints (must be before public endpoints to avoid conflicts)
	r.HandleFunc("/internal/trips/company/{company_id}", tripHandler.GetTripsByCompanyID).Methods("GET")

	// Trip endpoints
	r.HandleFunc("/trips", tripHandler.CreateTrip).Methods("POST")
	r.HandleFunc("/trips", tripHandler.GetTrips).Methods("GET")
	r.HandleFunc("/trips/vehicle/{vehicle_id}", tripHandler.GetTripsByVehicleID).Methods("GET")
	r.HandleFunc("/trips/driver/{driver_id}", tripHandler.GetTripsByDriverID).Methods("GET")
	r.HandleFunc("/trips/{id}", tripHandler.GetTrip).Methods("GET")
	r.HandleFunc("/trips/{id}", tripHandler.DeleteTrip).Methods("DELETE")
	r.HandleFunc("/trips/{id}/progress", tripHandler.UpdateTripProgress).Methods("PUT")
	r.HandleFunc("/trips/{id}/progress", tripHandler.GetTripProgress).Methods("GET")
	r.HandleFunc("/trips/{id}/logs", tripHandler.GetTripLogs).Methods("GET")

	// SSE endpoints
	r.HandleFunc("/events/{uuid}", sseHandler.HandleSSE).Methods("GET")
	r.HandleFunc("/events/{uuid}", sseHandler.HandleSessionUpdate).Methods("POST")
	r.HandleFunc("/events/session/subscription", sseHandler.HandleSessionSubscriptionUpdate).Methods("PUT")
	r.HandleFunc("/events/status", sseHandler.GetSSEStatus).Methods("GET")
	r.HandleFunc("/events/debug/{uuid}", sseHandler.GetSessionDebug).Methods("GET")

	// Sync endpoints (change tracking)
	r.HandleFunc("/main-hash", syncHandler.GetMainHash).Methods("GET")
	r.HandleFunc("/merge", syncHandler.TriggerMerge).Methods("POST")
	r.HandleFunc("/changes/unmerged", syncHandler.GetUnmergedBatches).Methods("GET")
	// Note: /routes/hash and /locations/hash are registered in their respective sections BEFORE {id} routes to avoid routing conflicts

	// Serve static files
	r.PathPrefix("/static/").Handler(http.StripPrefix("/static/", http.FileServer(http.Dir("static"))))
	r.HandleFunc("/test", func(w http.ResponseWriter, r *http.Request) {
		http.ServeFile(w, r, "static/sse-test.html")
	})
	r.HandleFunc("/test-filtered", func(w http.ResponseWriter, r *http.Request) {
		http.ServeFile(w, r, "static/sse-filtered-test.html")
	})

	return r
}

func healthCheck(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]string{
		"status":  "UP",
		"service": "cavgotrips",
	})
}

func rootHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]interface{}{
		"service": "cavgotrips",
		"version": "1.0.0",
		"endpoints": map[string]string{
			"health":    "/health",
			"locations": "/locations",
			"routes":    "/routes",
			"trips":     "/trips",
		},
	})
}
