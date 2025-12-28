package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"cavgoBooking/handlers"
	"cavgoBooking/models"
	"cavgoBooking/repository"
	"cavgoBooking/service"

	"github.com/gorilla/mux"
	"github.com/jmoiron/sqlx"
	"github.com/joho/godotenv"
	_ "github.com/lib/pq"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

func main() {
	// Load .env file (optional - will use environment variables if .env doesn't exist)
	if err := godotenv.Load(); err != nil {
		// This is expected in Docker environment, so only log as debug
		log.Printf("Info: .env file not found, using environment variables: %v", err)
	}

	// Load configuration
	config := loadConfig()

	// --- GORM Auto-Migration Block ---
	// Note: Trip snapshot uses sqlx with manual JSON columns, not GORM
	gormDB, err := gorm.Open(postgres.Open(config.DatabaseURL), &gorm.Config{})
	if err != nil {
		log.Fatalf("Failed to connect to database with GORM: %v", err)
	}
	if err := gormDB.AutoMigrate(
		&models.Booking{},
		&models.Ticket{},
		&models.Payment{},
		// Trip snapshots use sqlx, not GORM - see migration SQL
	); err != nil {
		log.Fatalf("Failed to auto-migrate tables: %v", err)
	}
	// --- End GORM Auto-Migration Block ---

	// Initialize database
	db, err := initDB(config.DatabaseURL)
	if err != nil {
		log.Fatal("Failed to initialize database:", err)
	}
	defer db.Close()

	// Initialize repositories
	bookingRepo := repository.NewBookingRepository(db)

	// Initialize RabbitMQ publisher and bundle publisher
	var rabbitPublisher *service.RabbitMQPublisher
	var bundlePublisher *service.BundlePublisher
	var rabbitConsumer *service.RabbitMQConsumer
	{
		rabbitHost := getEnv("RABBITMQ_HOST", "localhost")
		rabbitPort := getEnv("RABBITMQ_PORT", "5672")
		rabbitUser := getEnv("RABBITMQ_USER", "admin")
		rabbitPass := getEnv("RABBITMQ_PASS", "admin")
		rabbitExchange := getEnv("RABBITMQ_EXCHANGE", "bookings.fanout")
		rabbitURL := fmt.Sprintf("amqp://%s:%s@%s:%s/", rabbitUser, rabbitPass, rabbitHost, rabbitPort)

		// Queue names
		bundleQueueName := getEnv("BUNDLE_QUEUE_NAME", "bookingbundles.queue")
		bundleReplyQueueName := getEnv("BUNDLE_REPLY_QUEUE_NAME", "bookingbundles.reply.queue")

		log.Printf("[RabbitMQ] Connecting with:")
		log.Printf("  Host: %s", rabbitHost)
		log.Printf("  Port: %s", rabbitPort)
		log.Printf("  User: %s", rabbitUser)
		log.Printf("  Pass: %s", rabbitPass)
		log.Printf("  Exchange: %s", rabbitExchange)
		log.Printf("  Bundle Queue: %s", bundleQueueName)
		log.Printf("  Bundle Reply Queue: %s", bundleReplyQueueName)
		log.Printf("  URL: %s", rabbitURL)

		// Initialize fanout publisher
		publisher, err := service.NewRabbitMQPublisher(rabbitURL, rabbitExchange)
		if err != nil {
			log.Printf("Warning: Failed to initialize RabbitMQ publisher: %v", err)
		} else {
			rabbitPublisher = publisher
		}

		// Initialize bundle publisher
		bundlePub, err := service.NewBundlePublisher(rabbitURL, bundleReplyQueueName)
		if err != nil {
			log.Printf("Warning: Failed to initialize Bundle publisher: %v", err)
		} else {
			bundlePublisher = bundlePub
			log.Printf("[BundlePublisher] Successfully initialized with reply queue: %s", bundleReplyQueueName)
		}
	}

	// Initialize services
	tripService := service.NewHTTPTripService(config.TripServiceURL)

	// Initialize trip snapshot repository and service (db is already *sqlx.DB)
	tripSnapshotRepo := repository.NewTripSnapshotRepository(db)
	// Ensure trip snapshot schema exists (no external migrations needed)
	if err := tripSnapshotRepo.EnsureSchema(context.Background()); err != nil {
		log.Printf("Warning: Failed to ensure trip_snapshots schema: %v", err)
	} else {
		log.Printf("Trip snapshot schema ensured")
	}

	// Configure snapshot exchange for publisher
	snapshotExchange := getEnv("SNAPSHOT_EXCHANGE", "bookingservice.trip.snapshot")
	if rabbitPublisher != nil {
		rabbitPublisher.SetSnapshotExchange(snapshotExchange)
		log.Printf("[RabbitMQ] Snapshot exchange configured: %s", snapshotExchange)
	}

	tripSnapshotService := service.NewTripSnapshotService(tripSnapshotRepo, rabbitPublisher)

	// Remove MockUserService and update bookingService initialization
	// userService := &MockUserService{}
	bookingService := service.NewBookingService(bookingRepo, tripService, tripSnapshotService, rabbitPublisher, bundlePublisher)

	// Log booking service initialization
	if bundlePublisher != nil {
		log.Printf("[BookingService] Initialized with bundlePublisher (bundle reply queue enabled)")
	} else {
		log.Printf("[BookingService] WARNING: Initialized WITHOUT bundlePublisher (bundle reply queue disabled)")
	}

	// Start background booking monitor
	service.StartBookingMonitor(bookingRepo, rabbitPublisher, tripSnapshotService)

	// Initialize and start RabbitMQ consumer for booking bundles
	if bundlePublisher != nil {
		bundleQueueName := getEnv("BUNDLE_QUEUE_NAME", "bookingbundles.queue")
		bundleReplyQueueName := getEnv("BUNDLE_REPLY_QUEUE_NAME", "bookingbundles.reply.queue")
		rabbitHost := getEnv("RABBITMQ_HOST", "localhost")
		rabbitPort := getEnv("RABBITMQ_PORT", "5672")
		rabbitUser := getEnv("RABBITMQ_USER", "admin")
		rabbitPass := getEnv("RABBITMQ_PASS", "admin")
		rabbitURL := fmt.Sprintf("amqp://%s:%s@%s:%s/", rabbitUser, rabbitPass, rabbitHost, rabbitPort)

		consumer, err := service.NewRabbitMQConsumer(rabbitURL, bundleQueueName, bundleReplyQueueName, bundlePublisher, bookingService)
		if err != nil {
			log.Printf("Warning: Failed to initialize RabbitMQ consumer: %v", err)
		} else {
			rabbitConsumer = consumer
			// Start consuming messages
			ctx := context.Background()
			if err := consumer.StartConsuming(ctx); err != nil {
				log.Printf("Warning: Failed to start RabbitMQ consumer: %v", err)
			} else {
				log.Printf("RabbitMQ consumer started successfully, listening on queue: %s", bundleQueueName)
			}
		}
	}

	// Initialize Eureka service
	eurekaService := service.NewEurekaService()

	// Initialize handlers
	bookingHandler := handlers.NewBookingHandler(bookingService)

	// Setup routes
	router := mux.NewRouter()
	bookingHandler.RegisterRoutes(router)

	// Add middleware
	router.Use(loggingMiddleware)
	router.Use(corsMiddleware)

	// Health check endpoint
	router.HandleFunc("/health", healthCheck).Methods("GET")

	// Start server
	srv := &http.Server{
		Addr:         config.ServerAddress,
		Handler:      router,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// Start server first
	go func() {
		log.Printf("Starting booking service on %s", config.ServerAddress)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal("Server failed to start:", err)
		}
	}()

	// Wait a moment for server to start
	time.Sleep(2 * time.Second)

	// Register with Eureka after server is running
	log.Printf("Attempting to register with Eureka...")
	maxRetries := 5
	for i := 0; i < maxRetries; i++ {
		if err := eurekaService.Register(); err != nil {
			log.Printf("Warning: Failed to register with Eureka (attempt %d/%d): %v", i+1, maxRetries, err)
			if i < maxRetries-1 {
				log.Printf("Retrying in 5 seconds...")
				time.Sleep(5 * time.Second)
			}
		} else {
			log.Printf("Successfully registered with Eureka")
			// Start heartbeat
			eurekaService.StartHeartbeat()
			break
		}
	}

	// Wait for interrupt signal to gracefully shutdown
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("Shutting down server...")

	// Deregister from Eureka
	if err := eurekaService.Deregister(); err != nil {
		log.Printf("Warning: Failed to deregister from Eureka: %v", err)
	}

	// Close RabbitMQ publisher
	if rabbitPublisher != nil {
		rabbitPublisher.Close()
	}

	// Close bundle publisher
	if bundlePublisher != nil {
		bundlePublisher.Close()
	}

	// Close RabbitMQ consumer
	if rabbitConsumer != nil {
		rabbitConsumer.Close()
	}

	// Graceful shutdown
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		log.Fatal("Server forced to shutdown:", err)
	}

	log.Println("Server exited")
}

// Config holds application configuration
type Config struct {
	DatabaseURL     string
	ServerAddress   string
	Environment     string
	TripServiceURL  string
	EurekaServerURL string
	EurekaAppName   string
}

func loadConfig() *Config {
	config := &Config{
		DatabaseURL:     getEnv("DATABASE_URL", "postgres://postgres:postgres@localhost/cavgobooks?sslmode=disable"),
		ServerAddress:   getEnv("SERVER_ADDRESS", ":6030"),
		Environment:     getEnv("ENVIRONMENT", "development"),
		TripServiceURL:  getEnv("TRIP_SERVICE_URL", "http://localhost:6080"),
		EurekaServerURL: getEnv("EUREKA_SERVER_URL", "http://localhost:8761"),
		EurekaAppName:   getEnv("EUREKA_APP_NAME", "cavgobooking"),
	}

	// Debug logging
	log.Printf("Loaded configuration:")
	log.Printf("  - ServerAddress: %s", config.ServerAddress)
	log.Printf("  - EurekaServerURL: %s", config.EurekaServerURL)
	log.Printf("  - Environment: %s", config.Environment)
	log.Printf("  - TripServiceURL: %s", config.TripServiceURL)
	log.Printf("  - DatabaseURL: %s", config.DatabaseURL)

	return config
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

func initDB(databaseURL string) (*sqlx.DB, error) {
	db, err := sqlx.Connect("postgres", databaseURL)
	if err != nil {
		return nil, err
	}

	// Test connection
	if err := db.Ping(); err != nil {
		return nil, err
	}

	// Set connection pool settings
	db.SetMaxOpenConns(25)
	db.SetMaxIdleConns(25)
	db.SetConnMaxLifetime(5 * time.Minute)

	return db, nil
}

// Middleware functions
func loggingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		log.Printf("%s %s %s", r.Method, r.RequestURI, time.Since(start))
	})
}

func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")

		if r.Method == "OPTIONS" {
			w.WriteHeader(http.StatusOK)
			return
		}

		next.ServeHTTP(w, r)
	})
}

func healthCheck(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status": "healthy", "service": "booking-service"}`))
}
