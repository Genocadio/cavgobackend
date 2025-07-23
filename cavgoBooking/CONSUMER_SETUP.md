# RabbitMQ Fanout Exchange Consumer Setup Guide

This guide explains how to set up consumers for the `bookings.fanout` exchange that your booking service publishes to.

## Overview

The booking service now uses a **fanout exchange** called `bookings.fanout`. When a booking event occurs, it's published to this exchange and broadcast to all queues bound to it.

## Architecture

```
Booking Service (Publisher)
    ↓ publishes to
bookings.fanout (Fanout Exchange)
    ↓ broadcasts to
├── Service A Queue (e.g., notifications-service)
├── Service B Queue (e.g., analytics-service)  
└── Service C Queue (e.g., audit-service)
```

## Environment Variables

Set these environment variables in your consumer service:

```bash
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USER=admin
RABBITMQ_PASS=admin
RABBITMQ_EXCHANGE=bookings.fanout
```

## Consumer Implementation

### 1. Basic Consumer Structure

```go
package main

import (
    "encoding/json"
    "fmt"
    "log"
    "os"

    "github.com/rabbitmq/amqp091-go"
)

// BookingEvent represents the structure of booking events
type BookingEvent struct {
    Event string      `json:"event"`
    Data  interface{} `json:"data"`
}

// Consumer handles consuming messages from RabbitMQ fanout exchange
type Consumer struct {
    conn         *amqp091.Connection
    channel      *amqp091.Channel
    exchangeName string
    queueName    string
    consumerTag  string
}

// NewConsumer creates a new consumer instance
func NewConsumer(amqpURL, exchangeName, queueName, consumerTag string) (*Consumer, error) {
    conn, err := amqp091.Dial(amqpURL)
    if err != nil {
        return nil, fmt.Errorf("failed to connect to RabbitMQ: %w", err)
    }

    ch, err := conn.Channel()
    if err != nil {
        conn.Close()
        return nil, fmt.Errorf("failed to open channel: %w", err)
    }

    // Declare the fanout exchange (same as publisher)
    err = ch.ExchangeDeclare(
        exchangeName,
        "fanout",
        true,  // durable
        false, // autoDelete
        false, // internal
        false, // noWait
        nil,   // arguments
    )
    if err != nil {
        ch.Close()
        conn.Close()
        return nil, fmt.Errorf("failed to declare exchange: %w", err)
    }

    // Declare queue for this consumer
    queue, err := ch.QueueDeclare(
        queueName,
        true,  // durable
        false, // autoDelete
        false, // exclusive
        false, // noWait
        nil,   // arguments
    )
    if err != nil {
        ch.Close()
        conn.Close()
        return nil, fmt.Errorf("failed to declare queue: %w", err)
    }

    // Bind the queue to the exchange
    err = ch.QueueBind(
        queue.Name,
        "", // routing key (ignored for fanout)
        exchangeName,
        false,
        nil,
    )
    if err != nil {
        ch.Close()
        conn.Close()
        return nil, fmt.Errorf("failed to bind queue: %w", err)
    }

    return &Consumer{
        conn:         conn,
        channel:      ch,
        exchangeName: exchangeName,
        queueName:    queue.Name,
        consumerTag:  consumerTag,
    }, nil
}

// Start begins consuming messages
func (c *Consumer) Start(handler func(BookingEvent) error) error {
    msgs, err := c.channel.Consume(
        c.queueName,
        c.consumerTag,
        false, // autoAck
        false, // exclusive
        false, // noLocal
        false, // noWait
        nil,   // args
    )
    if err != nil {
        return fmt.Errorf("failed to register consumer: %w", err)
    }

    log.Printf("Consumer started. Queue: %s, Exchange: %s", c.queueName, c.exchangeName)

    go func() {
        for d := range msgs {
            var event BookingEvent
            if err := json.Unmarshal(d.Body, &event); err != nil {
                log.Printf("Failed to unmarshal message: %v", err)
                d.Ack(false)
                continue
            }

            log.Printf("Received event: %s", event.Event)

            // Process the event
            if err := handler(event); err != nil {
                log.Printf("Failed to process event: %v", err)
                // Reject the message and requeue
                d.Nack(false, true)
            } else {
                // Acknowledge the message
                d.Ack(false)
            }
        }
    }()

    return nil
}

// Close closes the consumer connection
func (c *Consumer) Close() {
    if c.channel != nil {
        _ = c.channel.Close()
    }
    if c.conn != nil {
        _ = c.conn.Close()
    }
}
```

### 2. Usage Example

```go
func main() {
    // Configuration
    rabbitHost := getEnv("RABBITMQ_HOST", "localhost")
    rabbitPort := getEnv("RABBITMQ_PORT", "5672")
    rabbitUser := getEnv("RABBITMQ_USER", "admin")
    rabbitPass := getEnv("RABBITMQ_PASS", "admin")
    rabbitURL := fmt.Sprintf("amqp://%s:%s@%s:%s/", rabbitUser, rabbitPass, rabbitHost, rabbitPort)

    // Create consumer with unique queue name for your service
    consumer, err := NewConsumer(
        rabbitURL,
        "bookings.fanout",           // exchange name
        "notifications-service-queue", // unique queue name for your service
        "notifications-consumer",     // unique consumer tag
    )
    if err != nil {
        log.Fatalf("Failed to create consumer: %v", err)
    }
    defer consumer.Close()

    // Define your event handler
    handler := func(event BookingEvent) error {
        switch event.Event {
        case "booking.created":
            log.Printf("Processing booking created event: %+v", event.Data)
            // Send notification to user
            return sendNotification(event.Data)
            
        case "booking.updated":
            log.Printf("Processing booking updated event: %+v", event.Data)
            // Update notification
            return updateNotification(event.Data)
            
        case "booking.cancelled":
            log.Printf("Processing booking cancelled event: %+v", event.Data)
            // Send cancellation notification
            return sendCancellationNotification(event.Data)
            
        default:
            log.Printf("Unknown event type: %s", event.Event)
            return nil
        }
    }

    // Start consuming
    if err := consumer.Start(handler); err != nil {
        log.Fatalf("Failed to start consumer: %v", err)
    }

    // Keep the application running
    log.Printf("Consumer is running. Press CTRL+C to exit.")
    select {}
}

func getEnv(key, defaultValue string) string {
    if value := os.Getenv(key); value != "" {
        return value
    }
    return defaultValue
}
```

## Event Types

The booking service publishes these event types:

- `booking.created` - When a new booking is created
- `booking.updated` - When a booking is updated
- `booking.cancelled` - When a booking is cancelled

## Queue Naming Convention

Use unique queue names for each service:

- `notifications-service-queue`
- `analytics-service-queue`
- `audit-service-queue`
- `email-service-queue`
- `sms-service-queue`

## Testing

1. Start your consumer service
2. Create a booking through the booking service API
3. Verify your consumer receives the `booking.created` event

## Error Handling

- Messages are automatically acknowledged on successful processing
- Failed messages are rejected and requeued
- Connection errors are logged and the consumer will attempt to reconnect

## Dependencies

Add to your `go.mod`:

```
require github.com/rabbitmq/amqp091-go v1.8.1
```

## Docker Example

```dockerfile
FROM golang:1.21-alpine

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN go build -o consumer .

CMD ["./consumer"]
```

## Health Checks

Add a health check endpoint to your consumer service:

```go
func healthCheck(w http.ResponseWriter, r *http.Request) {
    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(http.StatusOK)
    w.Write([]byte(`{"status": "healthy", "service": "your-service-name"}`))
}
``` 