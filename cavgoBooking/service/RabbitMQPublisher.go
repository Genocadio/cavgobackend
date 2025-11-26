package service

import (
	"encoding/json"
	"fmt"

	"cavgoBooking/models"

	"github.com/rabbitmq/amqp091-go"
)

// RabbitMQPublisher handles publishing messages to RabbitMQ fanout exchange
// Usage: publisher.PublishBookingEvent(eventType, bookingResponse)
type RabbitMQPublisher struct {
	conn         *amqp091.Connection
	channel      *amqp091.Channel
	exchangeName string
}

func NewRabbitMQPublisher(amqpURL, exchangeName string) (*RabbitMQPublisher, error) {
	conn, err := amqp091.Dial(amqpURL)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to RabbitMQ: %w", err)
	}
	ch, err := conn.Channel()
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("failed to open channel: %w", err)
	}

	// Declare the fanout exchange
	err = ch.ExchangeDeclare(
		exchangeName,
		"fanout", // exchange type
		true,     // durable
		false,    // autoDelete
		false,    // internal
		false,    // noWait
		nil,      // arguments
	)
	if err != nil {
		ch.Close()
		conn.Close()
		return nil, fmt.Errorf("failed to declare exchange: %w", err)
	}

	return &RabbitMQPublisher{conn: conn, channel: ch, exchangeName: exchangeName}, nil
}

func (p *RabbitMQPublisher) PublishBookingEvent(eventType string, bookingResponse interface{}) error {
	// Try to extract booking ID for logging
	bookingID := "unknown"
	if resp, ok := bookingResponse.(*models.BookingResponse); ok && resp != nil && resp.Booking != nil {
		bookingID = resp.Booking.ID
	}
	
	msg := map[string]interface{}{
		"event": eventType,
		"data":  bookingResponse,
	}
	body, err := json.Marshal(msg)
	if err != nil {
		fmt.Printf("[RabbitMQPublisher] [EXCHANGE=%s] ERROR: Failed to marshal message: event=%s bookingId=%s error=%v\n", 
			p.exchangeName, eventType, bookingID, err)
		return fmt.Errorf("failed to marshal message: %w", err)
	}

	// Log publishing details and payload
	fmt.Printf("[RabbitMQPublisher] [EXCHANGE=%s] PUBLISHING fanout: event=%s bookingId=%s bytes=%d\n", 
		p.exchangeName, eventType, bookingID, len(body))
	fmt.Printf("[RabbitMQPublisher] [EXCHANGE=%s] Payload preview (first 500 chars): %s\n", 
		p.exchangeName, truncateStringForLog(string(body), 500))
	
	err = p.channel.Publish(
		p.exchangeName, // publish to the exchange
		"",             // routing key is ignored for fanout exchanges
		false,          // mandatory
		false,          // immediate
		amqp091.Publishing{
			ContentType: "application/json",
			Body:        body,
		},
	)
	if err != nil {
		fmt.Printf("[RabbitMQPublisher] [EXCHANGE=%s] FAILED fanout publish: event=%s bookingId=%s err=%v\n", 
			p.exchangeName, eventType, bookingID, err)
		return err
	}
	fmt.Printf("[RabbitMQPublisher] [EXCHANGE=%s] PUBLISHED fanout: event=%s bookingId=%s\n", 
		p.exchangeName, eventType, bookingID)
	return nil
}

// truncateStringForLog truncates a string to maxLen characters for logging
func truncateStringForLog(s string, maxLen int) string {
	if len(s) <= maxLen {
		return s
	}
	return s[:maxLen] + "..."
}

func (p *RabbitMQPublisher) Close() {
	if p.channel != nil {
		_ = p.channel.Close()
	}
	if p.conn != nil {
		_ = p.conn.Close()
	}
}
