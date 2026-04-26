package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"cavgotrips/internal/config"
	"cavgotrips/internal/database"
	"cavgotrips/internal/handlers"
	"cavgotrips/internal/models"
	"cavgotrips/internal/repository"
	"cavgotrips/internal/router"
	"cavgotrips/internal/service"

	"github.com/joho/godotenv"
)

// Add helper to update trip seats
func updateTripSeats(tripService *service.TripService, tripID int64, newSeats int) error {
	updates := map[string]interface{}{"seats": newSeats, "updated_at": time.Now()}
	return tripService.UpdateTripFields(tripID, updates)
}

func main() {
	// Load environment variables from .env file if it exists
	if err := godotenv.Load(); err != nil {
		// Only log as info since environment variables can be set via Docker/container runtime
		log.Printf("Info: .env file not found, using environment variables from container: %v", err)
	}

	// Load configuration
	cfg := config.Load()

	// Initialize database
	db, err := database.Initialize(cfg.DatabaseURL)
	if err != nil {
		log.Fatal("Failed to initialize database:", err)
	}

	// Initialize repositories
	locationRepo := repository.NewLocationRepository(db)
	routeRepo := repository.NewRouteRepository(db)
	tripRepo := repository.NewTripRepository(db)

	// Setup RabbitMQ
	var rabbitMQService *service.RabbitMQService
	rabbitUser := cfg.RabbitMQ.User
	rabbitPass := cfg.RabbitMQ.Password
	rabbitHost := cfg.RabbitMQ.Host
	rabbitQueue := cfg.RabbitMQ.Queue
	rabbitURL := fmt.Sprintf("amqp://%s:%s@%s/", rabbitUser, rabbitPass, rabbitHost)
	rabbitMQService, err = service.NewRabbitMQService(rabbitURL, rabbitQueue)
	if err != nil {
		log.Fatalf("Failed to connect to RabbitMQ: %v", err)
	}
	defer rabbitMQService.Close()

	// Setup fanout exchange for trip service events
	tripExchangeName := "tripservice.trips.updates"
	if err := rabbitMQService.DeclareFanoutExchange(tripExchangeName, ""); err != nil {
		log.Printf("Failed to declare trip fanout exchange: %v", err)
	}

	// Initialize SSE session repository
	sseSessionRepo := repository.NewSSESessionRepository(db)

	// Initialize Session service
	sessionService := service.NewSessionService(sseSessionRepo)

	// Initialize SSE service
	sseService := service.NewSSEService(sessionService)

	// Initialize change tracking repository and service
	changeTrackingRepo := repository.NewChangeTrackingRepository(db)
	changeTrackingService := service.NewChangeTrackingService(changeTrackingRepo)

	// Start inactivity monitor for merge
	changeTrackingService.StartInactivityMonitor()

	// Initialize trip log repository and service
	tripLogRepo := repository.NewTripLogRepository(db)
	tripLogService := service.NewTripLogService(tripLogRepo, cfg.StoreLogs)

	// Initialize trip update scheduler and poster only if baseURL is configured
	var tripUpdateScheduler *service.TripUpdateScheduler
	var tripUpdatePoster *service.TripUpdatePoster
	if cfg.TripUpdateBaseURL != "" {
		tripUpdateScheduler = service.NewTripUpdateScheduler()
		tripUpdatePoster = service.NewTripUpdatePoster(cfg.TripUpdateBaseURL)
		log.Printf("[TripUpdate] Trip update posting enabled with baseURL: %s", cfg.TripUpdateBaseURL)
	} else {
		log.Printf("[TripUpdate] Trip update posting disabled (TRIP_UPDATE_BASE_URL not set)")
	}

	// Initialize services
	locationService := service.NewLocationService(locationRepo, changeTrackingService)
	routeService := service.NewRouteService(routeRepo, changeTrackingService)
	tripService := service.NewTripService(tripRepo, routeRepo, locationRepo, cfg.VehicleServiceURL, rabbitMQService, sseService, sessionService, tripLogService, tripUpdateScheduler, tripUpdatePoster)

	// Backfill remaining_seats for existing trips (sets to seats where null)
	if err := tripService.BackfillRemainingSeats(); err != nil {
		log.Printf("[TripInit] Failed to backfill remaining_seats: %v", err)
	}

	// Set the trip fanout exchange name for trip service
	tripService.SetTripExchange(tripExchangeName)

	// Start cleanup scheduler if logging is enabled
	if cfg.StoreLogs {
		tripLogService.StartCleanupScheduler()
		log.Printf("[TripLogService] Cleanup scheduler started")
	}

	// Listen to trip snapshots from booking service fanout exchange
	go func() {
		exchangeName := cfg.RabbitMQ.SnapshotExchange
		if exchangeName == "" {
			exchangeName = "bookingservice.trip.snapshot"
		}
		queueName := "cavgotrips.trip-snapshots"

		log.Printf("[TripSnapshot] 🚀 Initializing trip snapshots consumer...")
		log.Printf("[TripSnapshot] 🔗 Exchange: %s | Queue: %s", exchangeName, queueName)

		if err := rabbitMQService.DeclareFanoutExchange(exchangeName, queueName); err != nil {
			log.Printf("[TripSnapshot] ❌ Failed to setup exchange: %v", err)
			return
		}

		log.Printf("[TripSnapshot] ✅ Exchange and queue setup successful")
		log.Printf("[TripSnapshot] 📡 Starting snapshot listener...")

		err := rabbitMQService.ListenTripSnapshots(queueName, func(snapshot models.TripSnapshot) {
			tripService.HandleTripSnapshot(snapshot)
		})

		if err != nil {
			log.Printf("[TripSnapshot] ❌ Failed to start consumer: %v", err)
			return
		}

		log.Printf("[TripSnapshot] ✅ Snapshot consumer successfully started and listening")
	}()

	// Listen to Naviga trip updates from fanout exchange cavgomqt.trip.updates
	go func() {
		exchangeName := "cavgomqt.trip.updates"
		queueName := "cavgotrips.trip-updates"

		if err := rabbitMQService.DeclareFanoutExchange(exchangeName, queueName); err != nil {
			log.Printf("[Trip Fanout] Failed to setup exchange: %v", err)
			return
		}
		err := rabbitMQService.ListenFanoutQueue(queueName, func(body []byte) {
			// Log raw payload as received from RabbitMQ for traceability
			log.Printf("[Trip Fanout] Raw message: %s", string(body))
			var evt models.NavigaTripUpdateEvent
			if err := json.Unmarshal(body, &evt); err != nil {
				log.Printf("[Trip Fanout] Unmarshal error: %v body=%s", err, string(body))
				return
			}
			if pretty, err := json.MarshalIndent(evt, "", "  "); err == nil {
				log.Printf("[Trip Fanout] Parsed event: %s", string(pretty))
			}
			if _, err := tripService.UpdateTripFromNavigaEvent(evt); err != nil {
				log.Printf("[Trip Fanout] Failed to apply update for trip %d: %v", evt.Trip.ID, err)
			}
		})
		if err != nil {
			log.Printf("[Trip Fanout] Failed to start consumer: %v", err)
		}
	}()

	// Initialize Eureka service
	eurekaService := service.NewEurekaService(cfg)

	// Initialize handlers
	locationHandler := handlers.NewLocationHandler(locationService)
	routeHandler := handlers.NewRouteHandler(routeService)
	tripHandler := handlers.NewTripHandler(tripService, tripUpdateScheduler, tripUpdatePoster, cfg.TripUpdateBaseURL)
	sseHandler := handlers.NewSSEHandler(sseService)
	syncHandler := handlers.NewSyncHandler(changeTrackingService, routeService, locationService)

	// Setup router
	r := router.Setup(locationHandler, routeHandler, tripHandler, sseHandler, syncHandler)

	// Strict Eureka lifecycle startup order.
	eurekaService.EnsureRegistered()
	eurekaService.StartHeartbeat()
	eurekaService.StartRegistrationVerifier(90 * time.Second)

	// Start server
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	server := &http.Server{
		Addr:    ":" + port,
		Handler: r,
	}

	// Graceful shutdown
	go func() {
		fmt.Printf("Server starting on port %s\n", port)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal("Server error:", err)
		}
	}()

	// Wait for interrupt signal to gracefully shutdown the server
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("Shutting down server...")
	eurekaService.StopHeartbeat()
	eurekaService.StopRegistrationVerifier()

	// Deregister from Eureka
	if err := eurekaService.Deregister(); err != nil {
		log.Printf("Warning: Failed to deregister from Eureka: %v", err)
	}

	// Give outstanding requests a deadline for completion
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := server.Shutdown(ctx); err != nil {
		log.Fatal("Server forced to shutdown:", err)
	}

	log.Println("Server exited")
}
