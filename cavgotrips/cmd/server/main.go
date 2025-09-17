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

	// Initialize SSE session repository
	sseSessionRepo := repository.NewSSESessionRepository(db)

	// Initialize Session service
	sessionService := service.NewSessionService(sseSessionRepo)

	// Initialize SSE service
	sseService := service.NewSSEService(sessionService)

	// Initialize services
	locationService := service.NewLocationService(locationRepo)
	routeService := service.NewRouteService(routeRepo)
	tripService := service.NewTripService(tripRepo, routeRepo, locationRepo, cfg.VehicleServiceURL, rabbitMQService, sseService, sessionService)

	// Listen to booking events from fanout exchange
	go func() {
		exchangeName := cfg.RabbitMQ.Exchange
		queueName := "bookings.processor.queue"

		// Declare the fanout exchange and bind our queue to it
		err := rabbitMQService.DeclareFanoutExchange(exchangeName, queueName)
		if err != nil {
			log.Printf("[Booking MQ] Failed to setup fanout exchange: %v", err)
			return
		}

		err = rabbitMQService.ListenBookingEvents(queueName, func(event models.BookingEventMessage) {
			log.Printf("[Booking MQ] Received event: %+v", event)
			booking := event.Data.Booking
			tripID := booking.TripID
			numTickets := booking.NumberOfTickets
			paymentStatus := booking.Payment.Status
			paymentMethod := booking.Payment.PaymentMethod

			// Debug: log the full booking JSON
			bookingJSON, _ := json.MarshalIndent(booking, "", "  ")
			log.Printf("[Booking MQ] Booking JSON: %s", string(bookingJSON))

			switch paymentStatus {
			case "PENDING":
				trip, err := tripService.GetTripByID(tripID)
				if err != nil {
					log.Printf("[Booking MQ] Trip not found: %v", err)
					return
				}
				if trip.Seats < numTickets {
					log.Printf("[Booking MQ] Not enough seats for trip %d: have %d, need %d", tripID, trip.Seats, numTickets)
					return
				}
				err = updateTripSeats(tripService, tripID, trip.Seats-numTickets)
				if err != nil {
					log.Printf("[Booking MQ] Failed to update trip seats: %v", err)
					return
				}
				// Publish trip update since seats were reduced
				updatedTrip, err := tripService.GetTripByID(tripID)
				if err == nil {
					if rabbitMQService != nil {
						_ = rabbitMQService.PublishTripEvent("updated", *updatedTrip)
						log.Printf("[Booking MQ] Published trip update after reducing seats for trip %d", tripID)
					}
					// Broadcast SSE event for seat reduction
					if sseService != nil {
						log.Printf("[Booking MQ] 🎯 Broadcasting SSE 'seats_reduced' event for trip %d (seats: %d -> %d)",
							tripID, trip.Seats, updatedTrip.Seats)
						sseService.BroadcastTripEventToSessions(models.TripEventMessage{
							Event: "seats_reduced",
							Data:  *updatedTrip,
						})
						log.Printf("[Booking MQ] ✅ Successfully queued SSE 'seats_reduced' event for trip %d", tripID)
					} else {
						log.Printf("[Booking MQ] ⚠️  SSE service not available - cannot broadcast seat reduction event")
					}
				}
				log.Printf("[Booking MQ] Reduced seats for trip %d by %d", tripID, numTickets)
			case "FAILED":
				trip, err := tripService.GetTripByID(tripID)
				if err != nil {
					log.Printf("[Booking MQ] Trip not found: %v", err)
					return
				}
				err = updateTripSeats(tripService, tripID, trip.Seats+numTickets)
				if err != nil {
					log.Printf("[Booking MQ] Failed to add back seats: %v", err)
					return
				}
				// Publish trip update since seats were added back
				updatedTrip, err := tripService.GetTripByID(tripID)
				if err == nil {
					if rabbitMQService != nil {
						_ = rabbitMQService.PublishTripEvent("updated", *updatedTrip)
						log.Printf("[Booking MQ] Published trip update after adding back seats for trip %d", tripID)
					}
					// Broadcast SSE event for seat restoration
					if sseService != nil {
						log.Printf("[Booking MQ] 🎯 Broadcasting SSE 'seats_restored' event for trip %d (seats: %d -> %d)",
							tripID, trip.Seats, updatedTrip.Seats)
						sseService.BroadcastTripEventToSessions(models.TripEventMessage{
							Event: "seats_restored",
							Data:  *updatedTrip,
						})
						log.Printf("[Booking MQ] ✅ Successfully queued SSE 'seats_restored' event for trip %d", tripID)
					} else {
						log.Printf("[Booking MQ] ⚠️  SSE service not available - cannot broadcast seat restoration event")
					}
				}
				log.Printf("[Booking MQ] Added back %d seats to trip %d", numTickets, tripID)
			case "COMPLETED":
				// For CARD payment method, reduce seats as this might be a direct payment
				if paymentMethod == "CARD" {
					trip, err := tripService.GetTripByID(tripID)
					if err != nil {
						log.Printf("[Booking MQ] Trip not found: %v", err)
						return
					}
					if trip.Seats < numTickets {
						log.Printf("[Booking MQ] Not enough seats for trip %d: have %d, need %d", tripID, trip.Seats, numTickets)
						return
					}
					err = updateTripSeats(tripService, tripID, trip.Seats-numTickets)
					if err != nil {
						log.Printf("[Booking MQ] Failed to update trip seats: %v", err)
						return
					}
					// Publish trip update since seats were reduced
					updatedTrip, err := tripService.GetTripByID(tripID)
					if err == nil {
						if rabbitMQService != nil {
							_ = rabbitMQService.PublishTripEvent("updated", *updatedTrip)
							log.Printf("[Booking MQ] Published trip update after reducing seats for CARD payment on trip %d", tripID)
						}
						// Broadcast SSE event for seat reduction
						if sseService != nil {
							log.Printf("[Booking MQ] 🎯 Broadcasting SSE 'seats_reduced' event for CARD payment on trip %d (seats: %d -> %d)",
								tripID, trip.Seats, updatedTrip.Seats)
							sseService.BroadcastTripEventToSessions(models.TripEventMessage{
								Event: "seats_reduced",
								Data:  *updatedTrip,
							})
							log.Printf("[Booking MQ] ✅ Successfully queued SSE 'seats_reduced' event for CARD payment on trip %d", tripID)
						} else {
							log.Printf("[Booking MQ] ⚠️  SSE service not available - cannot broadcast seat reduction event")
						}
					}
					log.Printf("[Booking MQ] Reduced seats for trip %d by %d (CARD payment completed)", tripID, numTickets)
				} else {
					log.Printf("[Booking MQ] Payment completed for booking %s (payment method: %s) - no trip update needed", booking.ID, paymentMethod)
				}
			default:
				log.Printf("[Booking MQ] Unknown payment status: %s", paymentStatus)
			}
		})
		if err != nil {
			log.Printf("[Booking MQ] Failed to listen to booking events: %v", err)
		}
	}()

	// Create a separate RabbitMQ service instance for MQTT trip events
	tripsQueueName := "trips.publisher.queue"
	mqttRabbitMQService, err := service.NewRabbitMQService(rabbitURL, tripsQueueName)
	if err != nil {
		log.Printf("[MQTT Trip MQ] ❌ Failed to create RabbitMQ service for MQTT trips: %v", err)
	} else {
		defer mqttRabbitMQService.Close()

		// Listen to trip events from MQTT service
		go func() {
			log.Printf("[MQTT Trip MQ] 🚀 Starting MQTT trip listener setup...")
			log.Printf("[MQTT Trip MQ] 📋 Target queue: %s", tripsQueueName)
			log.Printf("[MQTT Trip MQ] 🔗 RabbitMQ connection: %s", rabbitURL)

			// Add a small delay to ensure RabbitMQ connection is ready
			time.Sleep(2 * time.Second)

			// List available queues for debugging
			mqttRabbitMQService.ListQueues()

			log.Printf("[MQTT Trip MQ] Setting up listener for queue: %s", tripsQueueName)

			err := mqttRabbitMQService.ListenMQTTTripEvents(tripsQueueName, func(event models.MQTTTripEventMessage) {
				log.Printf("[MQTT Trip MQ] ✅ Received trip event: %s for trip ID: %d", event.Event, event.Data.ID)

				// Log the full event structure for debugging
				eventJSON, _ := json.MarshalIndent(event, "", "  ")
				log.Printf("[MQTT Trip MQ] Full event data: %s", string(eventJSON))

				// Update the trip with data from MQTT service
				updatedTrip, err := tripService.UpdateTripFromMQTT(event.Data)
				if err != nil {
					log.Printf("[MQTT Trip MQ] ❌ Failed to update trip %d: %v", event.Data.ID, err)
					return
				}

				log.Printf("[MQTT Trip MQ] ✅ Successfully updated trip %d with event %s", event.Data.ID, event.Event)

				// Log the updated trip details for debugging
				tripJSON, _ := json.MarshalIndent(updatedTrip, "", "  ")
				log.Printf("[MQTT Trip MQ] Updated trip data: %s", string(tripJSON))
			})
			if err != nil {
				log.Printf("[MQTT Trip MQ] ❌ Failed to listen to trip events: %v", err)
			}
		}()
	}

	// Initialize Eureka service
	eurekaService := service.NewEurekaService(cfg)

	// Initialize handlers
	locationHandler := handlers.NewLocationHandler(locationService)
	routeHandler := handlers.NewRouteHandler(routeService)
	tripHandler := handlers.NewTripHandler(tripService)
	sseHandler := handlers.NewSSEHandler(sseService)

	// Setup router
	r := router.Setup(locationHandler, routeHandler, tripHandler, sseHandler)

	// Register with Eureka with retry mechanism
	go func() {
		maxRetries := 5
		retryDelay := 5 * time.Second

		for i := 0; i < maxRetries; i++ {
			if err := eurekaService.Register(); err != nil {
				log.Printf("Warning: Failed to register with Eureka (attempt %d/%d): %v", i+1, maxRetries, err)
				if i < maxRetries-1 {
					log.Printf("Retrying in %v...", retryDelay)
					time.Sleep(retryDelay)
				}
			} else {
				log.Printf("Successfully registered with Eureka")
				// Start heartbeat
				eurekaService.StartHeartbeat()
				return
			}
		}
		log.Printf("Failed to register with Eureka after %d attempts", maxRetries)
	}()

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
