package service

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"time"

	"cavgoBooking/models"

	"github.com/rabbitmq/amqp091-go"
)

// RabbitMQConsumer handles consuming messages from RabbitMQ
type RabbitMQConsumer struct {
	conn            *amqp091.Connection
	channel         *amqp091.Channel
	queueName       string
	replyQueueName  string
	bundlePublisher *BundlePublisher
	bookingService  BookingService
}

// BundlePublisher handles publishing booking bundles to reply queue
type BundlePublisher struct {
	conn      *amqp091.Connection
	channel   *amqp091.Channel
	queueName string
}

// NewRabbitMQConsumer creates a new RabbitMQ consumer
func NewRabbitMQConsumer(amqpURL, queueName, replyQueueName string, bundlePublisher *BundlePublisher, bookingService BookingService) (*RabbitMQConsumer, error) {
	conn, err := amqp091.Dial(amqpURL)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to RabbitMQ: %w", err)
	}

	ch, err := conn.Channel()
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("failed to open channel: %w", err)
	}

	// Declare the main queue
	_, err = ch.QueueDeclare(
		queueName,
		true,  // durable
		false, // delete when unused
		false, // exclusive
		false, // no-wait
		amqp091.Table{
			"x-dead-letter-exchange":     "",
			"x-dead-letter-routing-key": queueName + ".dlq",
		}, // arguments to match Spring config
	)
	if err != nil {
		ch.Close()
		conn.Close()
		return nil, fmt.Errorf("failed to declare queue: %w", err)
	}

	// Declare the reply queue
	_, err = ch.QueueDeclare(
		replyQueueName,
		true,  // durable
		false, // delete when unused
		false, // exclusive
		false, // no-wait
		amqp091.Table{
			"x-dead-letter-exchange":     "",
			"x-dead-letter-routing-key": replyQueueName + ".dlq",
		}, // arguments to match Spring config
	)
	if err != nil {
		ch.Close()
		conn.Close()
		return nil, fmt.Errorf("failed to declare reply queue: %w", err)
	}

	return &RabbitMQConsumer{
		conn:            conn,
		channel:         ch,
		queueName:       queueName,
		replyQueueName:  replyQueueName,
		bundlePublisher: bundlePublisher,
		bookingService:  bookingService,
	}, nil
}

// NewBundlePublisher creates a new bundle publisher
func NewBundlePublisher(amqpURL, replyQueueName string) (*BundlePublisher, error) {
	conn, err := amqp091.Dial(amqpURL)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to RabbitMQ: %w", err)
	}

	ch, err := conn.Channel()
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("failed to open channel: %w", err)
	}

	return &BundlePublisher{
		conn:      conn,
		channel:   ch,
		queueName: replyQueueName,
	}, nil
}

// StartConsuming starts consuming messages from the queue
func (c *RabbitMQConsumer) StartConsuming(ctx context.Context) error {
	msgs, err := c.channel.Consume(
		c.queueName, // queue
		"",          // consumer
		false,       // auto-ack
		false,       // exclusive
		false,       // no-local
		false,       // no-wait
		nil,         // args
	)
	if err != nil {
		return fmt.Errorf("failed to register consumer: %w", err)
	}

	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case msg := <-msgs:
				if err := c.processMessage(ctx, msg); err != nil {
					log.Printf("[RabbitMQConsumer] Error processing message: %v", err)
					// Reject and requeue the message
					msg.Nack(false, true)
				} else {
					// Acknowledge the message
					msg.Ack(false)
				}
			}
		}
	}()

	return nil
}

// processMessage processes a single message from the queue
func (c *RabbitMQConsumer) processMessage(ctx context.Context, msg amqp091.Delivery) error {
	// Log full delivery metadata and payload for observability
	log.Printf("[RabbitMQConsumer] Received delivery: tag=%d redelivered=%t exchange=%s routingKey=%s contentType=%s contentEncoding=%s correlationId=%s replyTo=%s messageId=%s appId=%s type=%s timestamp=%s",
		msg.DeliveryTag,
		msg.Redelivered,
		msg.Exchange,
		msg.RoutingKey,
		msg.ContentType,
		msg.ContentEncoding,
		msg.CorrelationId,
		msg.ReplyTo,
		msg.MessageId,
		msg.AppId,
		msg.Type,
		msg.Timestamp.Format(time.RFC3339),
	)
	if len(msg.Headers) > 0 {
		log.Printf("[RabbitMQConsumer] Headers: %+v", msg.Headers)
	}
	log.Printf("[RabbitMQConsumer] Payload (%d bytes): %s", len(msg.Body), string(msg.Body))

	var bundle models.BookingBundle
	if err := json.Unmarshal(msg.Body, &bundle); err != nil {
		return fmt.Errorf("failed to unmarshal bundle: %w", err)
	}

	// Convert bundle to internal models
	booking, payment, tickets := c.convertBundleToModels(&bundle)

	// Log extracted key identifiers
	log.Printf("[RabbitMQConsumer] Parsed bundle identifiers: tripId=%s bookingId=%s paymentId=%s tickets=%d",
		bundle.TripID,
		bundle.Booking.ID,
		bundle.Payment.ID,
		len(bundle.Tickets),
	)

	// Save booking data (without generating new IDs)
	if err := c.saveBundleData(ctx, booking, payment, tickets); err != nil {
		return fmt.Errorf("failed to save bundle data: %w", err)
	}

	// Create booking response for publishing
	booking.Tickets = tickets
	booking.Payment = payment
	resp := &models.BookingResponse{
		Booking:          booking,
		Message:          "Booking bundle processed successfully",
		PaymentReference: &payment.ID,
	}

	// Publish to fanout exchange for other services (but not to reply queue to avoid duplicates)
	if err := c.publishBundleEvent("created", resp); err != nil {
		log.Printf("[RabbitMQConsumer] Warning: Failed to publish bundle event: %v", err)
	}

	log.Printf("[RabbitMQConsumer] Successfully processed booking bundle for trip %s", bundle.TripID)
	return nil
}

// convertBundleToModels converts BookingBundle to internal models
func (c *RabbitMQConsumer) convertBundleToModels(bundle *models.BookingBundle) (*models.Booking, *models.Payment, []models.Ticket) {
	// Convert booking
	booking := &models.Booking{
		ID:                bundle.Booking.ID,
		TripID:            bundle.Booking.TripID,
		UserID:            bundle.Booking.UserID,
		UserEmail:         bundle.Booking.UserEmail,
		UserPhone:         bundle.Booking.UserPhone,
		UserName:          bundle.Booking.UserName,
		PickupLocationID:  bundle.Booking.PickupLocationID,
		DropoffLocationID: bundle.Booking.DropoffLocationID,
		NumberOfTickets:   bundle.Booking.NumberOfTickets,
		TotalAmount:       bundle.Booking.TotalAmount,
		Status:            bundle.Booking.Status,
		BookingReference:  bundle.Booking.BookingReference,
		CreatedAt:         time.Unix(bundle.Booking.CreatedAt/1000, 0),
		UpdatedAt:         time.Unix(bundle.Booking.UpdatedAt/1000, 0),
	}

	// Convert payment
	payment := &models.Payment{
		ID:            bundle.Payment.ID,
		BookingID:     bundle.Payment.BookingID,
		Amount:        bundle.Payment.Amount,
		PaymentMethod: bundle.Payment.PaymentMethod,
		Status:        bundle.Payment.Status,
		TransactionID: bundle.Payment.TransactionID,
		PaymentData:   bundle.Payment.PaymentData,
		CreatedAt:     time.Unix(bundle.Payment.CreatedAt/1000, 0),
		UpdatedAt:     time.Unix(bundle.Payment.UpdatedAt/1000, 0),
	}

	// Convert tickets
	tickets := make([]models.Ticket, len(bundle.Tickets))
	for i, bundleTicket := range bundle.Tickets {
		ticket := models.Ticket{
			ID:                  bundleTicket.ID,
			BookingID:           bundleTicket.BookingID,
			TicketNumber:        bundleTicket.TicketNumber,
			QRCode:              bundleTicket.QRCode,
			IsUsed:              bundleTicket.IsUsed,
			ValidatedBy:         bundleTicket.ValidatedBy,
			CreatedAt:           time.Unix(bundleTicket.CreatedAt/1000, 0),
			UpdatedAt:           time.Unix(bundleTicket.UpdatedAt/1000, 0),
			PickupLocationName:  bundleTicket.PickupLocationName,
			DropoffLocationName: bundleTicket.DropoffLocationName,
			CarPlate:            bundleTicket.CarPlate,
			CarCompany:          bundleTicket.CarCompany,
			PickupTime:          time.Unix(bundleTicket.PickupTime/1000, 0),
		}

		// Handle UsedAt field
		if bundleTicket.UsedAt != nil {
			usedAt := time.Unix(*bundleTicket.UsedAt/1000, 0)
			ticket.UsedAt = &usedAt
		}

		tickets[i] = ticket
	}

	return booking, payment, tickets
}

// saveBundleData saves the bundle data to the repository
func (c *RabbitMQConsumer) saveBundleData(ctx context.Context, booking *models.Booking, payment *models.Payment, tickets []models.Ticket) error {
	// Use repository directly to save without generating new IDs
	repo := c.bookingService.(*bookingService).bookingRepo

	// Set flag to indicate this booking came from RabbitMQ
	c.bookingService.(*bookingService).SetFromRabbitMQ(true)

	// Save booking
	if err := repo.CreateBooking(ctx, booking); err != nil {
		return fmt.Errorf("failed to save booking: %w", err)
	}

	// Save payment
	if err := repo.CreatePayment(ctx, payment); err != nil {
		return fmt.Errorf("failed to save payment: %w", err)
	}

	// Save tickets
	if err := repo.CreateTickets(ctx, tickets); err != nil {
		return fmt.Errorf("failed to save tickets: %w", err)
	}

	// Reset flag after processing
	c.bookingService.(*bookingService).SetFromRabbitMQ(false)

	return nil
}

// publishBundleEvent publishes booking events to fanout exchange only (not reply queue)
func (c *RabbitMQConsumer) publishBundleEvent(eventType string, resp *models.BookingResponse) error {
	// Get the fanout publisher from the booking service
	bookingService := c.bookingService.(*bookingService)
	if bookingService.rabbitPublisher == nil {
		return fmt.Errorf("rabbit publisher not available")
	}

	// Publish to fanout exchange for other services to consume
	if err := bookingService.rabbitPublisher.PublishBookingEvent(eventType, resp); err != nil {
		return fmt.Errorf("failed to publish to fanout exchange: %w", err)
	}

	log.Printf("[RabbitMQConsumer] Published bundle event '%s' to fanout exchange for booking %s", eventType, resp.Booking.ID)
	return nil
}

// PublishBundle publishes a booking bundle to the reply queue
func (p *BundlePublisher) PublishBundle(bundle *models.BookingBundle) error {
	body, err := json.Marshal(bundle)
	if err != nil {
		return fmt.Errorf("failed to marshal bundle: %w", err)
	}

	log.Printf("[BundlePublisher] Publishing bundle for trip %s", bundle.TripID)

	return p.channel.Publish(
		"",          // exchange
		p.queueName, // routing key
		false,       // mandatory
		false,       // immediate
		amqp091.Publishing{
			ContentType: "application/json",
			Body:        body,
		},
	)
}

// Close closes the consumer connections
func (c *RabbitMQConsumer) Close() {
	if c.channel != nil {
		_ = c.channel.Close()
	}
	if c.conn != nil {
		_ = c.conn.Close()
	}
}

// Close closes the bundle publisher connections
func (p *BundlePublisher) Close() {
	if p.channel != nil {
		_ = p.channel.Close()
	}
	if p.conn != nil {
		_ = p.conn.Close()
	}
}
