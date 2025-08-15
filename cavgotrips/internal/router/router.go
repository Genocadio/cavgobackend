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
	r.HandleFunc("/locations/{id}", locationHandler.GetLocation).Methods("GET")
	r.HandleFunc("/locations/{id}", locationHandler.UpdateLocation).Methods("PUT")
	r.HandleFunc("/locations/{id}", locationHandler.DeleteLocation).Methods("DELETE")

	// Route endpoints
	r.HandleFunc("/routes", routeHandler.CreateRoute).Methods("POST")
	r.HandleFunc("/routes", routeHandler.GetRoutes).Methods("GET")
	r.HandleFunc("/routes/{id}", routeHandler.GetRoute).Methods("GET")
	r.HandleFunc("/routes/{id}", routeHandler.UpdateRoute).Methods("PUT")
	r.HandleFunc("/routes/{id}", routeHandler.DeleteRoute).Methods("DELETE")
	r.HandleFunc("/routes/price-range", routeHandler.GetRoutesByPriceRange).Methods("GET")
	r.HandleFunc("/routes/distance-range", routeHandler.GetRoutesByDistanceRange).Methods("GET")
	r.HandleFunc("/routes/statistics", routeHandler.GetRouteStatistics).Methods("GET")

	// Trip endpoints
	r.HandleFunc("/trips", tripHandler.CreateTrip).Methods("POST")
	r.HandleFunc("/trips", tripHandler.GetTrips).Methods("GET")
	r.HandleFunc("/trips/vehicle/{vehicle_id}", tripHandler.GetTripsByVehicleID).Methods("GET")
	r.HandleFunc("/trips/{id}", tripHandler.GetTrip).Methods("GET")
	r.HandleFunc("/trips/{id}/progress", tripHandler.UpdateTripProgress).Methods("PUT")
	r.HandleFunc("/trips/{id}/progress", tripHandler.GetTripProgress).Methods("GET")

	// SSE endpoints
	r.HandleFunc("/events/{uuid}", sseHandler.HandleSSE).Methods("GET")
	r.HandleFunc("/events/{uuid}", sseHandler.HandleSessionUpdate).Methods("POST")
	r.HandleFunc("/events/session/subscription", sseHandler.HandleSessionSubscriptionUpdate).Methods("PUT")
	r.HandleFunc("/events/status", sseHandler.GetSSEStatus).Methods("GET")
	r.HandleFunc("/events/debug/{uuid}", sseHandler.GetSessionDebug).Methods("GET")

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
