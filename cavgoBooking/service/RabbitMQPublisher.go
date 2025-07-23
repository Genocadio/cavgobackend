package service

import (
	"encoding/json"
	"fmt"

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
	msg := map[string]interface{}{
		"event": eventType,
		"data":  bookingResponse,
	}
	body, err := json.Marshal(msg)
	fmt.Println("Publishing to RabbitMQ fanout exchange:", string(body))
	if err != nil {
		return fmt.Errorf("failed to marshal message: %w", err)
	}
	return p.channel.Publish(
		p.exchangeName, // publish to the exchange
		"",             // routing key is ignored for fanout exchanges
		false,          // mandatory
		false,          // immediate
		amqp091.Publishing{
			ContentType: "application/json",
			Body:        body,
		},
	)
}

func (p *RabbitMQPublisher) Close() {
	if p.channel != nil {
		_ = p.channel.Close()
	}
	if p.conn != nil {
		_ = p.conn.Close()
	}
}
