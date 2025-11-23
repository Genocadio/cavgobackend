package config

import (
	"os"
)

type Config struct {
	DatabaseURL       string
	Port              string
	Eureka            EurekaConfig
	VehicleServiceURL string
	// Add RabbitMQ config
	RabbitMQ RabbitMQConfig
	StoreLogs bool
	TripUpdateBaseURL string
}

type EurekaConfig struct {
	ServerURL          string
	AppName            string
	InstanceID         string
	RegisterWithEureka bool
	PreferIPAddress    bool
}

// Add RabbitMQConfig struct
type RabbitMQConfig struct {
	Host     string
	User     string
	Password string
	Queue    string
	Exchange string
}

func Load() *Config {
	return &Config{
		DatabaseURL: getEnv("DATABASE_URL", "host=localhost user=postgres password=postgres dbname=trip_service port=5432 sslmode=disable"),
		Port:        getEnv("PORT", "8080"),
		Eureka: EurekaConfig{
			ServerURL:          getEnv("EUREKA_SERVER_URL", "http://localhost:8761"),
			AppName:            getEnv("EUREKA_APP_NAME", "cavgotrips"),
			InstanceID:         getEnv("EUREKA_INSTANCE_ID", ""),
			RegisterWithEureka: getEnv("EUREKA_REGISTER", "true") == "true",
			PreferIPAddress:    getEnv("EUREKA_PREFER_IP", "true") == "true",
		},
		VehicleServiceURL: getEnv("VEHICLE_SERVICE_URL", "http://localhost:8060/main/vehicles/"),
		RabbitMQ: RabbitMQConfig{
			Host:     getEnv("RABBITMQ_HOST", "localhost:5672"),
			User:     getEnv("RABBITMQ_USER", "admin"),
			Password: getEnv("RABBITMQ_PASS", "admin"),
			Queue:    getEnv("RABBITMQ_QUEUE", "trips.queue"),
			Exchange: getEnv("RABBITMQ_EXCHANGE", "bookings.fanout"),
		},
		StoreLogs: getEnv("STORE_LOGS", "false") == "true",
		TripUpdateBaseURL: getEnv("TRIP_UPDATE_BASE_URL", ""),
	}
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}
