package service

import (
	"cavgotrips/internal/models"
	"encoding/json"
	"fmt"
	"log"

	amqp "github.com/rabbitmq/amqp091-go"
)

type RabbitMQService struct {
	conn    *amqp.Connection
	channel *amqp.Channel
	queue   amqp.Queue
}

func NewRabbitMQService(url, queueName string) (*RabbitMQService, error) {
	conn, err := amqp.Dial(url)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to RabbitMQ: %w", err)
	}
	ch, err := conn.Channel()
	if err != nil {
		return nil, fmt.Errorf("failed to open channel: %w", err)
	}
	q, err := ch.QueueDeclare(
		queueName,
		true,  // durable
		false, // autoDelete
		false, // exclusive
		false, // noWait
		amqp.Table{
			"x-dead-letter-exchange":    "",
			"x-dead-letter-routing-key": queueName + ".dlq",
		},
	)
	if err != nil {
		return nil, fmt.Errorf("failed to declare queue: %w", err)
	}
	return &RabbitMQService{conn, ch, q}, nil
}

func (r *RabbitMQService) PublishTripEvent(event string, trip models.Trip) error {
	msg := models.TripEventMessage{
		Event: event,
		Data:  trip,
	}
	body, err := json.Marshal(msg)
	if err != nil {
		return err
	}

	// Log the full JSON structure to the console
	log.Printf("[RabbitMQ Publish] Event: %s, Message: %s\n", event, string(body))

	return r.channel.Publish(
		"",           // exchange
		r.queue.Name, // routing key
		false,        // mandatory
		false,        // immediate
		amqp.Publishing{
			ContentType: "application/json",
			Body:        body,
		},
	)
}

func (r *RabbitMQService) ListenTripEvents(handler func(models.TripEventMessage)) error {
	msgs, err := r.channel.Consume(
		r.queue.Name,
		"",    // consumer
		true,  // auto-ack
		false, // exclusive
		false, // no-local
		false, // no-wait
		nil,   // args
	)
	if err != nil {
		return err
	}
	go func() {
		for d := range msgs {
			var event models.TripEventMessage
			if err := json.Unmarshal(d.Body, &event); err != nil {
				log.Printf("Failed to unmarshal trip event: %v", err)
				continue
			}
			handler(event)
		}
	}()
	return nil
}

// ListenBookingEvents listens for booking events from the bookings.queue
func (r *RabbitMQService) ListenBookingEvents(queueName string, handler func(models.BookingEventMessage)) error {
	log.Printf("[Booking MQ] Setting up consumer for queue: %s", queueName)
	msgs, err := r.channel.Consume(
		queueName,
		"",    // consumer
		true,  // auto-ack
		false, // exclusive
		false, // no-local
		false, // no-wait
		nil,   // args
	)
	if err != nil {
		log.Printf("[Booking MQ] ERROR: Failed to start consumer for queue %s: %v", queueName, err)
		return err
	}
	log.Printf("[Booking MQ] Consumer started for queue: %s", queueName)
	go func() {
		defer func() {
			if r := recover(); r != nil {
				log.Printf("[Booking MQ] PANIC in booking consumer goroutine: %v", r)
			}
		}()
		log.Printf("[Booking MQ] Booking consumer goroutine running for queue: %s", queueName)
		for d := range msgs {
			log.Printf("[Booking MQ] Raw message: %s", string(d.Body))
			var event models.BookingEventMessage
			if err := json.Unmarshal(d.Body, &event); err != nil {
				log.Printf("Failed to unmarshal booking event: %v", err)
				continue
			}
			handler(event)
		}
		log.Printf("[Booking MQ] Consumer goroutine for queue %s has exited (channel closed)", queueName)
	}()
	return nil
}

// DeclareFanoutExchange declares a fanout exchange and binds a queue to it
func (r *RabbitMQService) DeclareFanoutExchange(exchangeName, queueName string) error {
	// Declare the fanout exchange
	err := r.channel.ExchangeDeclare(
		exchangeName, // name
		"fanout",     // type
		true,         // durable
		false,        // auto-deleted
		false,        // internal
		false,        // no-wait
		nil,          // arguments
	)
	if err != nil {
		return fmt.Errorf("failed to declare fanout exchange: %w", err)
	}

	// Declare the queue
	q, err := r.channel.QueueDeclare(
		queueName, // name
		true,      // durable
		false,     // delete when unused
		false,     // exclusive
		false,     // no-wait
		nil,       // arguments
	)
	if err != nil {
		return fmt.Errorf("failed to declare queue: %w", err)
	}

	// Bind the queue to the exchange
	err = r.channel.QueueBind(
		q.Name,       // queue name
		"",           // routing key (empty for fanout)
		exchangeName, // exchange
		false,        // no-wait
		nil,          // arguments
	)
	if err != nil {
		return fmt.Errorf("failed to bind queue to exchange: %w", err)
	}

	log.Printf("[RabbitMQ] Successfully declared fanout exchange '%s' and bound queue '%s' to it", exchangeName, queueName)
	return nil
}

func (r *RabbitMQService) Close() {
	r.channel.Close()
	r.conn.Close()
}
