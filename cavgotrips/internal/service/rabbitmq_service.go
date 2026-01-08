package service

import (
	"cavgotrips/internal/models"
	"encoding/json"
	"fmt"
	"log"

	amqp "github.com/rabbitmq/amqp091-go"
)

// Helper function to get map keys for debugging
func getMapKeys(m map[string]interface{}) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	return keys
}

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
	log.Printf("[RabbitMQ Publish] Queue: %s, Event: %s, Message: %s\n", r.queue.Name, event, string(body))

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

// PublishTripEventToExchange publishes trip events to a fanout exchange
func (r *RabbitMQService) PublishTripEventToExchange(exchangeName string, event string, trip models.Trip) error {
	msg := models.TripEventMessage{
		Event: event,
		Data:  trip,
	}
	body, err := json.Marshal(msg)
	if err != nil {
		return err
	}

	// Log the full JSON structure to the console
	log.Printf("[RabbitMQ Publish Fanout] Exchange: %s, Event: %s, Message: %s\n", exchangeName, event, string(body))

	return r.channel.Publish(
		exchangeName, // fanout exchange
		"",           // routing key (empty for fanout)
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

// ListenTripSnapshots listens for trip snapshot messages from the booking snapshot fanout exchange
func (r *RabbitMQService) ListenTripSnapshots(queueName string, handler func(models.TripSnapshot)) error {
	log.Printf("[TripSnapshot MQ] Setting up consumer for queue: %s", queueName)

	msgs, err := r.channel.Consume(
		queueName,
		"",    // consumer
		false, // auto-ack (manual ack for reliability)
		false, // exclusive
		false, // no-local
		false, // no-wait
		nil,   // args
	)
	if err != nil {
		log.Printf("[TripSnapshot MQ] ERROR: Failed to start consumer for queue %s: %v", queueName, err)
		return err
	}

	log.Printf("[TripSnapshot MQ] ✅ Consumer started for queue: %s", queueName)
	log.Printf("[TripSnapshot MQ] ⏳ Waiting for snapshot messages on queue: %s", queueName)

	go func() {
		defer func() {
			if r := recover(); r != nil {
				log.Printf("[TripSnapshot MQ] 🚨 PANIC in snapshot consumer goroutine: %v", r)
			}
		}()

		log.Printf("[TripSnapshot MQ] 🎯 Snapshot consumer goroutine started - listening on queue: %s", queueName)
		messageCount := 0

		for d := range msgs {
			messageCount++
			log.Printf("[TripSnapshot MQ] 📨 Message #%d received from queue %s (size: %d bytes)",
				messageCount, queueName, len(d.Body))
			log.Printf("[TripSnapshot MQ] 📋 Raw message content: %s", string(d.Body))

			var snapshot models.TripSnapshot
			if err := json.Unmarshal(d.Body, &snapshot); err != nil {
				log.Printf("[TripSnapshot MQ] ❌ Failed to unmarshal snapshot message: %v", err)
				log.Printf("[TripSnapshot MQ] 🔍 Problematic message: %s", string(d.Body))
				d.Nack(false, false) // Don't requeue malformed messages
				continue
			}

			log.Printf("[TripSnapshot MQ] ✅ Successfully unmarshaled snapshot")
			log.Printf("[TripSnapshot MQ] 📊 Trip: %s | Status: %s | Available Seats: %d | Occupied: %d | Pending: %d",
				snapshot.TripID, snapshot.TripStatus,
				snapshot.Capacity.AvailableSeats,
				snapshot.Capacity.OccupiedSeats,
				snapshot.Capacity.PendingPaymentSeats)
			log.Printf("[TripSnapshot MQ] ⏰ Snapshot timestamp: %s", snapshot.LastUpdated)

			log.Printf("[TripSnapshot MQ] 🔄 Calling handler to process snapshot for trip %s", snapshot.TripID)
			handler(snapshot)
			log.Printf("[TripSnapshot MQ] ✅ Handler completed for snapshot #%d", messageCount)

			d.Ack(false) // Acknowledge successful processing
			log.Printf("[TripSnapshot MQ] 📤 Message #%d acknowledged and removed from queue", messageCount)
		}

		log.Printf("[TripSnapshot MQ] 🛑 Consumer goroutine for queue %s has exited (channel closed)", queueName)
		log.Printf("[TripSnapshot MQ] 📊 Total snapshots processed: %d", messageCount)
	}()

	return nil
}

// ListenMQTTTripEvents listens for trip events from the MQTT service
func (r *RabbitMQService) ListenMQTTTripEvents(queueName string, handler func(models.MQTTTripEventMessage)) error {
	log.Printf("[MQTT Trip MQ] Setting up consumer for queue: %s", queueName)

	// Check if channel is open
	if r.channel == nil {
		return fmt.Errorf("RabbitMQ channel is nil")
	}

	// Check if connection is open
	if r.conn == nil {
		return fmt.Errorf("RabbitMQ connection is nil")
	}

	// First, check if the queue exists
	_, err := r.channel.QueueInspect(queueName)
	if err != nil {
		log.Printf("[MQTT Trip MQ] Queue %s does not exist, trying to declare it: %v", queueName, err)
		// Try to declare the queue if it doesn't exist
		_, err = r.channel.QueueDeclare(
			queueName,
			true,  // durable
			false, // delete when unused
			false, // exclusive
			false, // no-wait
			nil,   // arguments
		)
		if err != nil {
			log.Printf("[MQTT Trip MQ] ERROR: Failed to declare queue %s: %v", queueName, err)
			return err
		}
		log.Printf("[MQTT Trip MQ] ✅ Queue %s declared successfully", queueName)
	} else {
		log.Printf("[MQTT Trip MQ] ✅ Queue %s already exists", queueName)
	}

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
		log.Printf("[MQTT Trip MQ] ERROR: Failed to start consumer for queue %s: %v", queueName, err)
		return err
	}
	log.Printf("[MQTT Trip MQ] ✅ Consumer started for queue: %s", queueName)
	go func() {
		defer func() {
			if r := recover(); r != nil {
				log.Printf("[MQTT Trip MQ] PANIC in trip consumer goroutine: %v", r)
			}
		}()
		log.Printf("[MQTT Trip MQ] 🎯 Trip consumer goroutine started and waiting for messages on queue: %s", queueName)
		log.Printf("[MQTT Trip MQ] 📡 Consumer is ready to receive messages...")
		for d := range msgs {
			log.Printf("[MQTT Trip MQ] Raw message received: %s", string(d.Body))

			// First, try to unmarshal as generic JSON to understand the structure
			var genericData map[string]interface{}
			if err := json.Unmarshal(d.Body, &genericData); err != nil {
				log.Printf("[MQTT Trip MQ] ❌ Failed to unmarshal as generic JSON: %v", err)
				log.Printf("[MQTT Trip MQ] Raw message that failed: %s", string(d.Body))
				continue
			}

			log.Printf("[MQTT Trip MQ] Message structure: %+v", genericData)

			// Now try to unmarshal as MQTTTripEventMessage
			var event models.MQTTTripEventMessage
			if err := json.Unmarshal(d.Body, &event); err != nil {
				log.Printf("[MQTT Trip MQ] ❌ Failed to unmarshal trip event: %v", err)
				log.Printf("[MQTT Trip MQ] Expected structure: {event: string, data: Trip}")
				log.Printf("[MQTT Trip MQ] Actual structure keys: %v", getMapKeys(genericData))
				continue
			}

			log.Printf("[MQTT Trip MQ] ✅ Successfully unmarshaled trip event: %s", event.Event)
			handler(event)
		}
		log.Printf("[MQTT Trip MQ] Consumer goroutine for queue %s has exited (channel closed)", queueName)
	}()
	return nil
}

// ListQueues lists all available queues (for debugging)
func (r *RabbitMQService) ListQueues() error {
	log.Printf("[MQTT Trip MQ] 🔍 Listing available queues...")

	// Try to get queue info
	queue, err := r.channel.QueueInspect("trips.publisher.queue")
	if err != nil {
		log.Printf("[MQTT Trip MQ] ❌ Queue 'trips.publisher.queue' not found: %v", err)
	} else {
		log.Printf("[MQTT Trip MQ] ✅ Queue 'trips.publisher.queue' found - Messages: %d, Consumers: %d", queue.Messages, queue.Consumers)
	}

	// Try other possible queue names
	possibleQueues := []string{"trips.queue", "trips.publisher.queue"}
	for _, queueName := range possibleQueues {
		queue, err := r.channel.QueueInspect(queueName)
		if err != nil {
			log.Printf("[MQTT Trip MQ] ❌ Queue '%s' not found: %v", queueName, err)
		} else {
			log.Printf("[MQTT Trip MQ] ✅ Queue '%s' found - Messages: %d, Consumers: %d", queueName, queue.Messages, queue.Consumers)
		}
	}

	return nil
}

func (r *RabbitMQService) Close() {
	r.channel.Close()
	r.conn.Close()
}

// ListenFanoutQueue consumes JSON messages from a bound queue and unmarshals into dest type via handler
func (r *RabbitMQService) ListenFanoutQueue(queueName string, rawHandler func([]byte)) error {
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
		return fmt.Errorf("failed to start consumer for queue %s: %w", queueName, err)
	}

	go func() {
		for d := range msgs {
			rawHandler(d.Body)
		}
		log.Printf("[RabbitMQ] Consumer for queue %s exited", queueName)
	}()
	return nil
}
