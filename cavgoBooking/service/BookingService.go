package service

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"cavgoBooking/models"
	"cavgoBooking/repository"

	"github.com/google/uuid"
)

type BookingService interface {
	CreateBooking(ctx context.Context, req *models.BookingRequest) (*models.BookingResponse, error)
	GetBookingByID(ctx context.Context, id string) (*models.Booking, error)
	GetBookingByReference(ctx context.Context, reference string) (*models.Booking, error)
	GetBookingsByTripID(ctx context.Context, tripID int) ([]models.Booking, error)
	GetBookingsByUserID(ctx context.Context, userID string) ([]models.Booking, error)
	CancelBooking(ctx context.Context, id string) error

	ValidateTicket(ctx context.Context, req *models.TicketValidationRequest) (*models.Ticket, error)
	GetTicketsByBookingID(ctx context.Context, bookingID string) ([]models.Ticket, error)

	ProcessPayment(ctx context.Context, bookingID string, paymentData *string) (*models.BookingResponse, error)
	RefundPayment(ctx context.Context, bookingID string) error

	// Bundle methods
	CreateBookingBundle(ctx context.Context, booking *models.Booking, payment *models.Payment, tickets []models.Ticket) (*models.BookingBundle, error)
	SetFromRabbitMQ(fromRabbitMQ bool)
}

type bookingService struct {
	bookingRepo     repository.BookingRepository
	tripService     TripService        // Interface to trip service
	rabbitPublisher *RabbitMQPublisher // Add publisher
	bundlePublisher *BundlePublisher   // Add bundle publisher
	fromRabbitMQ    bool               // Track if booking came from RabbitMQ
}

// TripService interface for trip service integration (now HTTP-based)
type TripService interface {
	GetTripByID(ctx context.Context, tripID int) (*models.Trip, error)
	ValidateTripBooking(ctx context.Context, tripID int, pickupLocationID, dropoffLocationID string, numberOfTickets int) error
}

// Remove UserService interface and struct
// type UserService interface {
// 	GetUserByID(ctx context.Context, userID string) (*User, error)
// 	ValidateUser(ctx context.Context, userID string) error
// }

// Remove User struct
// type User struct { ... }

func NewBookingService(bookingRepo repository.BookingRepository, tripService TripService, publisher *RabbitMQPublisher, bundlePublisher *BundlePublisher) BookingService {
	return &bookingService{
		bookingRepo:     bookingRepo,
		tripService:     tripService,
		rabbitPublisher: publisher,
		bundlePublisher: bundlePublisher,
		fromRabbitMQ:    false,
	}
}

func (s *bookingService) CreateBooking(ctx context.Context, req *models.BookingRequest) (*models.BookingResponse, error) {
	fmt.Printf("[BookingService] [STEP 1/8] Starting booking creation: tripId=%d pickupLocationId=%s dropoffLocationId=%s numberOfTickets=%d userName=%s userPhone=%s\n", 
		req.TripID, req.PickupLocationID, req.DropoffLocationID, req.NumberOfTickets, req.UserName, req.UserPhone)

	// Validate request (user_name and user_phone required)
	fmt.Printf("[BookingService] [STEP 2/8] Validating booking request...\n")
	if err := s.validateBookingRequest(ctx, req); err != nil {
		fmt.Printf("[BookingService] [STEP 2/8] Validation FAILED: %v\n", err)
		return nil, fmt.Errorf("validation failed: %w", err)
	}
	fmt.Printf("[BookingService] [STEP 2/8] Validation PASSED\n")

	// Validate trip availability and get trip
	fmt.Printf("[BookingService] [STEP 3/8] Fetching trip details: tripId=%d\n", req.TripID)
	trip, err := s.tripService.GetTripByID(ctx, req.TripID)
	if err != nil {
		fmt.Printf("[BookingService] [STEP 3/8] ERROR: Failed to fetch trip: %v\n", err)
		return nil, fmt.Errorf("trip validation failed: %w", err)
	}
	fmt.Printf("[BookingService] [STEP 3/8] Trip fetched: tripId=%d status=%s seats=%d\n", trip.ID, trip.Status, trip.Seats)
	
	if trip.Status != "SCHEDULED" && trip.Status != "IN_PROGRESS" {
		fmt.Printf("[BookingService] [STEP 3/8] ERROR: Trip not available: status=%s\n", trip.Status)
		return nil, fmt.Errorf("trip is not available: status is %s", trip.Status)
	}

	// Ensure requested tickets do not exceed or equal available seats
	if req.NumberOfTickets >= trip.Seats {
		fmt.Printf("[BookingService] [STEP 3/8] ERROR: Too many tickets requested: requested=%d available=%d\n", req.NumberOfTickets, trip.Seats)
		return nil, fmt.Errorf("requested number of tickets (%d) must be less than available seats (%d)", req.NumberOfTickets, trip.Seats)
	}
	fmt.Printf("[BookingService] [STEP 3/8] Seat availability check PASSED\n")

	// Calculate price based on pickup and dropoff
	fmt.Printf("[BookingService] [STEP 4/8] Calculating price: pickupLocationId=%s dropoffLocationId=%s\n", req.PickupLocationID, req.DropoffLocationID)
	pickupOrder := -1
	pickupPrice := 0.0
	if fmt.Sprintf("%d", trip.Route.OriginID) == req.PickupLocationID {
		pickupOrder = -1
		pickupPrice = 0.0
		fmt.Printf("[BookingService] [STEP 4/8] Pickup is at origin (order=-1, price=0.0)\n")
	} else {
		for _, wp := range trip.Waypoints {
			if fmt.Sprintf("%d", wp.LocationID) == req.PickupLocationID {
				pickupOrder = wp.Order
				pickupPrice = wp.Price
				fmt.Printf("[BookingService] [STEP 4/8] Pickup is at waypoint: order=%d price=%.2f\n", wp.Order, wp.Price)
				break
			}
		}
	}

	dropoffOrder := -1
	dropoffPrice := 0.0
	if fmt.Sprintf("%d", trip.Route.DestinationID) == req.DropoffLocationID {
		dropoffOrder = 999999
		dropoffPrice = trip.Route.RoutePrice
		fmt.Printf("[BookingService] [STEP 4/8] Dropoff is at destination (order=999999, price=%.2f)\n", trip.Route.RoutePrice)
	} else {
		for _, wp := range trip.Waypoints {
			if fmt.Sprintf("%d", wp.LocationID) == req.DropoffLocationID {
				dropoffOrder = wp.Order
				dropoffPrice = wp.Price
				fmt.Printf("[BookingService] [STEP 4/8] Dropoff is at waypoint: order=%d price=%.2f\n", wp.Order, wp.Price)
				break
			}
		}
	}

	if pickupOrder == -1 && dropoffOrder == 999999 {
		// Origin to destination, use route price
		dropoffPrice = trip.Route.RoutePrice
		pickupPrice = 0.0
	}

	if pickupOrder >= dropoffOrder {
		fmt.Printf("[BookingService] [STEP 4/8] ERROR: Invalid location order: pickupOrder=%d dropoffOrder=%d\n", pickupOrder, dropoffOrder)
		return nil, fmt.Errorf("incorrect location: pickup must be before dropoff")
	}

	pricePerTicket := dropoffPrice - pickupPrice
	if pricePerTicket < 0 {
		pricePerTicket = 0
	}
	totalAmount := pricePerTicket * float64(req.NumberOfTickets)
	fmt.Printf("[BookingService] [STEP 4/8] Price calculated: pricePerTicket=%.2f numberOfTickets=%d totalAmount=%.2f\n", pricePerTicket, req.NumberOfTickets, totalAmount)

	// Create booking
	fmt.Printf("[BookingService] [STEP 5/8] Creating booking record...\n")
	bookingID := uuid.New().String()
	bookingReference := s.generateBookingReference()
	booking := &models.Booking{
		ID:                bookingID,
		TripID:            req.TripID,
		PickupLocationID:  req.PickupLocationID,
		DropoffLocationID: req.DropoffLocationID,
		NumberOfTickets:   req.NumberOfTickets,
		TotalAmount:       totalAmount,
		Status:            models.BookingStatusPending,
		BookingReference:  bookingReference,
		CreatedAt:         time.Now(),
		UpdatedAt:         time.Now(),
		UserID:            req.UserID,
		UserEmail:         req.UserEmail,
		UserPhone:         req.UserPhone,
		UserName:          req.UserName,
	}
	fmt.Printf("[BookingService] [STEP 5/8] Booking object created: bookingId=%s bookingReference=%s status=%s\n", bookingID, bookingReference, booking.Status)

	// Save booking
	fmt.Printf("[BookingService] [STEP 5/8] Saving booking to database...\n")
	if err := s.bookingRepo.CreateBooking(ctx, booking); err != nil {
		fmt.Printf("[BookingService] [STEP 5/8] ERROR: Failed to save booking: bookingId=%s error=%v\n", bookingID, err)
		return nil, fmt.Errorf("failed to create booking: %w", err)
	}
	fmt.Printf("[BookingService] [STEP 5/8] Booking saved successfully: bookingId=%s\n", bookingID)

	// Create tickets
	fmt.Printf("[BookingService] [STEP 6/8] Generating %d tickets...\n", req.NumberOfTickets)
	tickets := s.generateTickets(booking.ID, req.NumberOfTickets, trip, req.PickupLocationID, req.DropoffLocationID)
	fmt.Printf("[BookingService] [STEP 6/8] Generated %d tickets: bookingId=%s\n", len(tickets), booking.ID)
	for i, ticket := range tickets {
		fmt.Printf("[BookingService] [STEP 6/8] Ticket %d: ticketId=%s ticketNumber=%s qrCode=%s\n", 
			i+1, ticket.ID, ticket.TicketNumber, ticket.QRCode)
	}
	
	fmt.Printf("[BookingService] [STEP 6/8] Saving tickets to database...\n")
	if err := s.bookingRepo.CreateTickets(ctx, tickets); err != nil {
		fmt.Printf("[BookingService] [STEP 6/8] ERROR: Failed to save tickets: bookingId=%s error=%v\n", booking.ID, err)
		return nil, fmt.Errorf("failed to create tickets: %w", err)
	}
	fmt.Printf("[BookingService] [STEP 6/8] Tickets saved successfully: bookingId=%s count=%d\n", booking.ID, len(tickets))

	// Create payment record
	fmt.Printf("[BookingService] [STEP 7/8] Creating payment record...\n")
	paymentID := uuid.New().String()
	payment := &models.Payment{
		ID:            paymentID,
		BookingID:     booking.ID,
		Amount:        totalAmount,
		PaymentMethod: req.PaymentMethod,
		Status:        models.PaymentStatusPending,
		PaymentData:   req.PaymentData,
		CreatedAt:     time.Now(),
		UpdatedAt:     time.Now(),
	}
	fmt.Printf("[BookingService] [STEP 7/8] Payment object created: paymentId=%s bookingId=%s amount=%.2f status=%s method=%s\n", 
		paymentID, booking.ID, totalAmount, payment.Status, req.PaymentMethod)

	fmt.Printf("[BookingService] [STEP 7/8] Saving payment to database...\n")
	if err := s.bookingRepo.CreatePayment(ctx, payment); err != nil {
		fmt.Printf("[BookingService] [STEP 7/8] ERROR: Failed to save payment: paymentId=%s error=%v\n", paymentID, err)
		return nil, fmt.Errorf("failed to create payment: %w", err)
	}
	fmt.Printf("[BookingService] [STEP 7/8] Payment saved successfully: paymentId=%s\n", paymentID)

	// Load full booking with relations
	booking.Tickets = tickets
	booking.Payment = payment

	resp := &models.BookingResponse{
		Booking:          booking,
		Message:          "Booking created successfully",
		PaymentReference: &payment.ID,
	}

	// Publish to RabbitMQ (ignore error, but log)
	fmt.Printf("[BookingService] [STEP 8/8] Publishing booking events to RabbitMQ: bookingId=%s\n", booking.ID)
	s.publishBookingEvents("created", resp)
	fmt.Printf("[BookingService] [STEP 8/8] Finished publishing booking events: bookingId=%s\n", booking.ID)
	fmt.Printf("[BookingService] [COMPLETE] Booking creation successful: bookingId=%s bookingReference=%s paymentId=%s tickets=%d\n", 
		booking.ID, booking.BookingReference, payment.ID, len(tickets))

	return resp, nil
}

func (s *bookingService) GetBookingByID(ctx context.Context, id string) (*models.Booking, error) {
	booking, err := s.bookingRepo.GetBookingByID(ctx, id)
	if err != nil {
		return nil, fmt.Errorf("failed to get booking: %w", err)
	}

	// Load tickets
	tickets, err := s.bookingRepo.GetTicketsByBookingID(ctx, id)
	if err != nil {
		return nil, fmt.Errorf("failed to get tickets: %w", err)
	}
	booking.Tickets = tickets

	// Load payment
	payment, err := s.bookingRepo.GetPaymentByBookingID(ctx, id)
	if err != nil {
		return nil, fmt.Errorf("failed to get payment: %w", err)
	}
	booking.Payment = payment

	// If booking is canceled and payment is failed, set tickets to nil
	if booking.Status == models.BookingStatusCanceled && payment.Status == models.PaymentStatusFailed {
		booking.Tickets = nil
	}

	return booking, nil
}

func (s *bookingService) GetBookingByReference(ctx context.Context, reference string) (*models.Booking, error) {
	booking, err := s.bookingRepo.GetBookingByReference(ctx, reference)
	if err != nil {
		return nil, fmt.Errorf("failed to get booking: %w", err)
	}

	// Load tickets
	tickets, err := s.bookingRepo.GetTicketsByBookingID(ctx, booking.ID)
	if err != nil {
		return nil, fmt.Errorf("failed to get tickets: %w", err)
	}
	booking.Tickets = tickets

	// Load payment
	payment, err := s.bookingRepo.GetPaymentByBookingID(ctx, booking.ID)
	if err == nil {
		booking.Payment = payment
		if booking.Status == models.BookingStatusCanceled && payment.Status == models.PaymentStatusFailed {
			booking.Tickets = nil
		}
	}

	return booking, nil
}

func (s *bookingService) GetBookingsByTripID(ctx context.Context, tripID int) ([]models.Booking, error) {
	bookings, err := s.bookingRepo.GetBookingsByTripID(ctx, tripID)
	if err != nil {
		return nil, err
	}

	for i := range bookings {
		// Load tickets
		tickets, err := s.bookingRepo.GetTicketsByBookingID(ctx, bookings[i].ID)
		if err == nil {
			bookings[i].Tickets = tickets
		}
		// Load payment
		payment, err := s.bookingRepo.GetPaymentByBookingID(ctx, bookings[i].ID)
		if err == nil {
			bookings[i].Payment = payment
			if bookings[i].Status == models.BookingStatusCanceled && payment.Status == models.PaymentStatusFailed {
				bookings[i].Tickets = nil
			}
		}
	}

	return bookings, nil
}

func (s *bookingService) GetBookingsByUserID(ctx context.Context, userID string) ([]models.Booking, error) {
	bookings, err := s.bookingRepo.GetBookingsByUserID(ctx, userID)
	if err != nil {
		return nil, err
	}

	for i := range bookings {
		// Load tickets
		tickets, err := s.bookingRepo.GetTicketsByBookingID(ctx, bookings[i].ID)
		if err == nil {
			bookings[i].Tickets = tickets
		}
		// Load payment
		payment, err := s.bookingRepo.GetPaymentByBookingID(ctx, bookings[i].ID)
		if err == nil {
			bookings[i].Payment = payment
			if bookings[i].Status == models.BookingStatusCanceled && payment.Status == models.PaymentStatusFailed {
				bookings[i].Tickets = nil
			}
		}
	}

	return bookings, nil
}

func (s *bookingService) CancelBooking(ctx context.Context, id string) error {
	booking, err := s.bookingRepo.GetBookingByID(ctx, id)
	if err != nil {
		return fmt.Errorf("failed to get booking: %w", err)
	}

	if booking.Status != models.BookingStatusPending && booking.Status != models.BookingStatusConfirmed {
		return fmt.Errorf("booking cannot be canceled in current status: %s", booking.Status)
	}

	// Update booking status
	if err := s.bookingRepo.UpdateBookingStatus(ctx, id, models.BookingStatusCanceled); err != nil {
		return fmt.Errorf("failed to cancel booking: %w", err)
	}

	// Process refund if payment was completed
	payment, err := s.bookingRepo.GetPaymentByBookingID(ctx, id)
	if err != nil {
		return fmt.Errorf("failed to get payment: %w", err)
	}

	if payment.Status == models.PaymentStatusCompleted {
		return s.RefundPayment(ctx, id)
	}

	return nil
}

func (s *bookingService) ValidateTicket(ctx context.Context, req *models.TicketValidationRequest) (*models.Ticket, error) {
	// Get ticket by QR code
	ticket, err := s.bookingRepo.GetTicketByQRCode(ctx, req.QRCode)
	if err != nil {
		return nil, fmt.Errorf("ticket not found: %w", err)
	}

	// Verify ticket number matches
	if ticket.TicketNumber != req.TicketNumber {
		return nil, fmt.Errorf("ticket number mismatch")
	}

	// Check if ticket is already used
	if ticket.IsUsed {
		return nil, fmt.Errorf("ticket already used")
	}

	// Get booking to check status
	booking, err := s.bookingRepo.GetBookingByID(ctx, ticket.BookingID)
	if err != nil {
		return nil, fmt.Errorf("failed to get booking: %w", err)
	}

	if booking.Status != models.BookingStatusConfirmed {
		return nil, fmt.Errorf("booking not confirmed")
	}

	// Validate ticket
	if err := s.bookingRepo.ValidateTicket(ctx, ticket.ID, req.ValidatedBy); err != nil {
		return nil, fmt.Errorf("failed to validate ticket: %w", err)
	}

	// Update ticket status
	ticket.IsUsed = true
	now := time.Now()
	ticket.UsedAt = &now
	ticket.ValidatedBy = &req.ValidatedBy
	ticket.UpdatedAt = now

	return ticket, nil
}

func (s *bookingService) GetTicketsByBookingID(ctx context.Context, bookingID string) ([]models.Ticket, error) {
	return s.bookingRepo.GetTicketsByBookingID(ctx, bookingID)
}

func (s *bookingService) ProcessPayment(ctx context.Context, bookingID string, paymentData *string) (*models.BookingResponse, error) {
	payment, err := s.bookingRepo.GetPaymentByBookingID(ctx, bookingID)
	if err != nil {
		return nil, fmt.Errorf("failed to get payment: %w", err)
	}

	// Mock payment processing - just mark as completed
	transactionID := s.generateTransactionID()

	// Update payment status
	if err := s.bookingRepo.UpdatePaymentStatus(ctx, payment.ID, models.PaymentStatusCompleted, &transactionID); err != nil {
		return nil, fmt.Errorf("failed to update payment status: %w", err)
	}

	// Update booking status
	if err := s.bookingRepo.UpdateBookingStatus(ctx, bookingID, models.BookingStatusConfirmed); err != nil {
		return nil, fmt.Errorf("failed to update booking status: %w", err)
	}

	// Load full booking with relations
	booking, err := s.bookingRepo.GetBookingByID(ctx, bookingID)
	if err != nil {
		return nil, fmt.Errorf("failed to get booking for response: %w", err)
	}
	tickets, err := s.bookingRepo.GetTicketsByBookingID(ctx, bookingID)
	if err != nil {
		return nil, fmt.Errorf("failed to get tickets for response: %w", err)
	}
	payment, err = s.bookingRepo.GetPaymentByBookingID(ctx, bookingID)
	if err != nil {
		return nil, fmt.Errorf("failed to get payment for response: %w", err)
	}
	booking.Tickets = tickets
	booking.Payment = payment

	resp := &models.BookingResponse{
		Booking:          booking,
		Message:          "Payment processed successfully",
		PaymentReference: &payment.ID,
	}

	// Publish to RabbitMQ (ignore error, but log)
	fmt.Printf("[ProcessPayment] About to publish booking events for booking %s\n", bookingID)
	s.publishBookingEvents("paid", resp)
	fmt.Printf("[ProcessPayment] Finished publishing booking events for booking %s\n", bookingID)

	return resp, nil
}

func (s *bookingService) RefundPayment(ctx context.Context, bookingID string) error {
	payment, err := s.bookingRepo.GetPaymentByBookingID(ctx, bookingID)
	if err != nil {
		return fmt.Errorf("failed to get payment: %w", err)
	}

	if payment.Status != models.PaymentStatusCompleted {
		return fmt.Errorf("payment not completed, cannot refund")
	}

	// Simulate refund processing logic here
	// In real implementation, integrate with payment gateway

	refundTransactionID := s.generateTransactionID()

	// Update payment status
	if err := s.bookingRepo.UpdatePaymentStatus(ctx, payment.ID, models.PaymentStatusRefunded, &refundTransactionID); err != nil {
		return fmt.Errorf("failed to update payment status: %w", err)
	}

	return nil
}

// Helper methods

func (s *bookingService) validateBookingRequest(ctx context.Context, req *models.BookingRequest) error {
	// Require user_name and user_phone
	if req.UserName == "" {
		return fmt.Errorf("user_name is required")
	}
	if req.UserPhone == "" {
		return fmt.Errorf("user_phone is required")
	}
	// Validate pickup and dropoff locations are different
	if req.PickupLocationID == req.DropoffLocationID {
		return fmt.Errorf("pickup and dropoff locations cannot be the same")
	}

	// Validate trip availability and get trip
	trip, err := s.tripService.GetTripByID(ctx, req.TripID)
	if err != nil {
		return fmt.Errorf("trip validation failed: %w", err)
	}
	if trip.Status != "SCHEDULED" && trip.Status != "IN_PROGRESS" {
		return fmt.Errorf("trip is not available: status is %s", trip.Status)
	}

	// Additional pickup/dropoff validation
	if req.PickupLocationID == fmt.Sprintf("%d", trip.Route.DestinationID) {
		return fmt.Errorf("pickup location cannot be the route destination")
	}
	if req.DropoffLocationID == fmt.Sprintf("%d", trip.Route.OriginID) {
		return fmt.Errorf("dropoff location cannot be the route origin")
	}

	pickupIsWaypoint := false
	var pickupWaypoint, dropoffWaypoint *models.TripWaypoint
	if req.PickupLocationID != fmt.Sprintf("%d", trip.Route.OriginID) {
		for i, wp := range trip.Waypoints {
			if fmt.Sprintf("%d", wp.LocationID) == req.PickupLocationID {
				pickupIsWaypoint = true
				pickupWaypoint = &trip.Waypoints[i]
				break
			}
		}
	}
	dropoffIsWaypoint := false
	if req.DropoffLocationID != fmt.Sprintf("%d", trip.Route.DestinationID) {
		for i, wp := range trip.Waypoints {
			if fmt.Sprintf("%d", wp.LocationID) == req.DropoffLocationID {
				dropoffIsWaypoint = true
				dropoffWaypoint = &trip.Waypoints[i]
				break
			}
		}
	}
	if pickupIsWaypoint && dropoffIsWaypoint {
		if pickupWaypoint.Order >= dropoffWaypoint.Order {
			return fmt.Errorf("incorrect location: pickup must be before dropoff")
		}
		if pickupWaypoint.IsPassed || dropoffWaypoint.IsPassed {
			return fmt.Errorf("incorrect location: pickup or dropoff waypoint already passed")
		}
	}

	// CityRoute-specific pickup validation
	if !trip.Route.CityRoute {
		if trip.Status == "SCHEDULED" {
			// Only origin can be pickup
			if req.PickupLocationID != fmt.Sprintf("%d", trip.Route.OriginID) {
				return fmt.Errorf("for non-city routes in SCHEDULED status, only the origin can be the pickup location")
			}
		} else if trip.Status == "IN_PROGRESS" {
			// Origin cannot be pickup, only the next waypoint
			if req.PickupLocationID == fmt.Sprintf("%d", trip.Route.OriginID) {
				return fmt.Errorf("for non-city routes in IN_PROGRESS status, origin cannot be the pickup location")
			}
			isNextWaypoint := false
			for _, wp := range trip.Waypoints {
				if fmt.Sprintf("%d", wp.LocationID) == req.PickupLocationID && wp.IsNext {
					isNextWaypoint = true
					break
				}
			}
			if !isNextWaypoint {
				return fmt.Errorf("for non-city routes in IN_PROGRESS status, only the next waypoint can be the pickup location")
			}
		}
	}

	return nil
}

func (s *bookingService) generateBookingReference() string {
	return fmt.Sprintf("BK-%d", time.Now().Unix())
}

func (s *bookingService) generateTickets(bookingID string, count int, trip *models.Trip, pickupLocationID, dropoffLocationID string) []models.Ticket {
	now := time.Now()

	// Helper to get location name
	getLocationName := func(loc *models.Location) string {
		if loc == nil {
			return ""
		}
		if loc.CustomName != nil && *loc.CustomName != "" {
			return *loc.CustomName
		}
		if loc.GooglePlaceName != nil && *loc.GooglePlaceName != "" {
			return *loc.GooglePlaceName
		}
		return ""
	}

	// Find pickup location name
	var pickupName string
	var pickupWaypoint *models.TripWaypoint
	if fmt.Sprintf("%d", trip.Route.OriginID) == pickupLocationID {
		pickupName = getLocationName(&trip.Route.Origin)
	} else {
		for _, wp := range trip.Waypoints {
			if fmt.Sprintf("%d", wp.LocationID) == pickupLocationID {
				pickupName = getLocationName(&wp.Location)
				pickupWaypoint = &wp
				break
			}
		}
	}

	// Find dropoff location name
	var dropoffName string
	if fmt.Sprintf("%d", trip.Route.DestinationID) == dropoffLocationID {
		dropoffName = getLocationName(&trip.Route.Destination)
	} else {
		for _, wp := range trip.Waypoints {
			if fmt.Sprintf("%d", wp.LocationID) == dropoffLocationID {
				dropoffName = getLocationName(&wp.Location)
				break
			}
		}
	}

	// Determine pickup time
	var pickupTime time.Time
	if trip.Status == "SCHEDULED" {
		pickupTime = time.Unix(trip.DepartureTime, 0)
	} else if pickupWaypoint != nil && pickupWaypoint.RemainingTime != nil {
		pickupTime = now.Add(time.Duration(*pickupWaypoint.RemainingTime) * time.Second)
	} else {
		pickupTime = now
	}

	tickets := make([]models.Ticket, count)
	for i := 0; i < count; i++ {
		ticketID := uuid.New().String()
		tickets[i] = models.Ticket{
			ID:                  ticketID,
			BookingID:           bookingID,
			TicketNumber:        s.generateTicketNumber(),
			QRCode:              s.generateQRCode(ticketID),
			IsUsed:              false,
			CreatedAt:           now,
			UpdatedAt:           now,
			PickupLocationName:  pickupName,
			DropoffLocationName: dropoffName,
			CarPlate:            trip.CarPlate,
			CarCompany:          trip.CarCompany,
			PickupTime:          pickupTime,
		}
	}

	return tickets
}

func (s *bookingService) generateTicketNumber() string {
	// Generate a 6-digit ticket number using current time and random component
	// This ensures uniqueness while keeping it exactly 6 digits
	now := time.Now()
	// Use nanoseconds to get more randomness, then mod to get 6 digits
	randomPart := now.Nanosecond() % 1000000 // Get last 6 digits
	// Ensure it's always 6 digits by padding with zeros if needed
	return fmt.Sprintf("%06d", randomPart)
}

func (s *bookingService) generateQRCode(ticketID string) string {
	// In real implementation, generate actual QR code
	return fmt.Sprintf("QR-%s", ticketID)
}

func (s *bookingService) generateTransactionID() string {
	return fmt.Sprintf("TXN-%s", uuid.New().String())
}

// HTTPTripService implements TripService by fetching from external HTTP API

type HTTPTripService struct {
	BaseURL string
}

func NewHTTPTripService(baseURL string) TripService {
	return &HTTPTripService{BaseURL: baseURL}
}

func (s *HTTPTripService) GetTripByID(ctx context.Context, tripID int) (*models.Trip, error) {
	url := fmt.Sprintf("%s/trips/%d", s.BaseURL, tripID)
	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("failed to fetch trip: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("unexpected status code: %d", resp.StatusCode)
	}

	var trip models.Trip
	if err := json.NewDecoder(resp.Body).Decode(&trip); err != nil {
		return nil, fmt.Errorf("failed to decode trip: %w", err)
	}
	return &trip, nil
}

func (s *HTTPTripService) ValidateTripBooking(ctx context.Context, tripID int, pickupLocationID, dropoffLocationID string, numberOfTickets int) error {
	trip, err := s.GetTripByID(ctx, tripID)
	if err != nil {
		if err.Error() == "unexpected status code: 404" || contains404(err.Error()) {
			return fmt.Errorf("trip not found")
		}
		return fmt.Errorf("failed to fetch trip: %w", err)
	}

	if trip.Status != "SCHEDULED" && trip.Status != "IN_PROGRESS" {
		return fmt.Errorf("trip is not available: status is %s", trip.Status)
	}

	// --- Pickup Location Validation ---
	pickupOrder := -1
	pickupIDFound := false
	if pickupLocationID != "" {
		// Check against origin
		if fmt.Sprintf("%d", trip.Route.OriginID) == pickupLocationID {
			pickupOrder = -1 // Origin is before all waypoints
			pickupIDFound = true
		} else {
			// Check waypoints
			for _, wp := range trip.Waypoints {
				if fmt.Sprintf("%d", wp.LocationID) == pickupLocationID {
					pickupOrder = wp.Order
					pickupIDFound = true
					break
				}
			}
		}
		if !pickupIDFound {
			return fmt.Errorf("incorrect location: pickup location not found in trip")
		}
	}

	// --- Dropoff Location Validation ---
	dropoffOrder := -1
	dropoffIDFound := false
	if dropoffLocationID != "" {
		// Check against destination
		if fmt.Sprintf("%d", trip.Route.DestinationID) == dropoffLocationID {
			dropoffOrder = 999999 // Destination is after all waypoints
			dropoffIDFound = true
		} else {
			// Check waypoints
			for _, wp := range trip.Waypoints {
				if fmt.Sprintf("%d", wp.LocationID) == dropoffLocationID {
					dropoffOrder = wp.Order
					dropoffIDFound = true
					break
				}
			}
		}
		if !dropoffIDFound {
			return fmt.Errorf("incorrect location: dropoff location not found in trip")
		}
	}

	// --- Order Validation ---
	if pickupIDFound && dropoffIDFound {
		if pickupOrder >= dropoffOrder {
			return fmt.Errorf("incorrect location: pickup must be before dropoff")
		}
	}

	return nil
}

// contains404 checks if the error string contains '404'.
func contains404(s string) bool {
	return (len(s) >= 3 && (s == "404" || (len(s) > 3 && (s[:3] == "404" || s[len(s)-3:] == "404")))) || (len(s) > 0 && (stringContains(s, "404")))
}

func stringContains(s, substr string) bool {
	return len(substr) > 0 && len(s) >= len(substr) && (s == substr || (len(s) > len(substr) && (s[:len(substr)] == substr || s[len(s)-len(substr):] == substr || (len(s) > len(substr)+1 && contains(s, substr)))))
}

func contains(s, substr string) bool {
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return true
		}
	}
	return false
}

// publishBookingEvents publishes to both fanout exchange and bundle reply queue
func (s *bookingService) publishBookingEvents(eventType string, resp *models.BookingResponse) {
	// Always publish to fanout exchange
	if s.rabbitPublisher != nil {
		err := s.rabbitPublisher.PublishBookingEvent(eventType, resp)
		if err != nil {
			fmt.Printf("[RabbitMQ] Failed to publish booking %s event: %v\n", eventType, err)
		} else {
			fmt.Printf("[RabbitMQ] Successfully published booking %s event to fanout exchange\n", eventType)
		}
	} else {
		fmt.Printf("[RabbitMQ] Warning: rabbitPublisher is nil, skipping fanout exchange publish\n")
	}

	// Only publish to bundle reply queue if booking didn't come from RabbitMQ
	if !s.fromRabbitMQ && s.bundlePublisher != nil {
		// Extra diagnostics
		queueName := s.bundlePublisher.QueueName()
		fmt.Printf("[BundlePublisher] Preparing to publish bundle: event=%s fromRabbitMQ=%t queue=%s bookingId=%s paymentId=%s tickets=%d\n",
			eventType, s.fromRabbitMQ, queueName, resp.Booking.ID, resp.Booking.Payment.ID, len(resp.Booking.Tickets))
		bundle, err := s.CreateBookingBundle(context.Background(), resp.Booking, resp.Booking.Payment, resp.Booking.Tickets)
		if err != nil {
			fmt.Printf("[BundlePublisher] Failed to create bundle for %s event: %v\n", eventType, err)
			return
		}

		fmt.Printf("[BundlePublisher] Bundle created, attempting publish to queue=%s sizeTickets=%d\n", queueName, len(bundle.Tickets))
		err = s.bundlePublisher.PublishBundle(bundle)
		if err != nil {
			fmt.Printf("[BundlePublisher] Failed to publish bundle for %s event: %v\n", eventType, err)
		} else {
			fmt.Printf("[BundlePublisher] Successfully published bundle for %s event to reply queue=%s bookingId=%s\n", eventType, queueName, bundle.Booking.ID)
		}
	} else {
		if s.fromRabbitMQ {
			fmt.Printf("[BundlePublisher] Skipping bundle publish: event=%s reason=fromRabbitMQ bookingId=%s\n", eventType, resp.Booking.ID)
		} else {
			fmt.Printf("[BundlePublisher] Skipping bundle publish: event=%s reason=bundlePublisher=nil\n", eventType)
		}
	}
}

// CreateBookingBundle creates a booking bundle from internal models
func (s *bookingService) CreateBookingBundle(ctx context.Context, booking *models.Booking, payment *models.Payment, tickets []models.Ticket) (*models.BookingBundle, error) {
	fmt.Printf("[CreateBookingBundle] Creating bundle for booking %s\n", booking.ID)
	fmt.Printf("[CreateBookingBundle] Booking status: %s, Payment status: %s, Tickets count: %d\n",
		booking.Status, payment.Status, len(tickets))

	// Convert booking to TripBooking
	tripBooking := models.TripBooking{
		ID:                booking.ID,
		TripID:            booking.TripID,
		UserID:            booking.UserID,
		UserEmail:         booking.UserEmail,
		UserPhone:         booking.UserPhone,
		UserName:          booking.UserName,
		PickupLocationID:  booking.PickupLocationID,
		DropoffLocationID: booking.DropoffLocationID,
		NumberOfTickets:   booking.NumberOfTickets,
		TotalAmount:       booking.TotalAmount,
		Status:            booking.Status,
		BookingReference:  booking.BookingReference,
		CreatedAt:         booking.CreatedAt.UnixMilli(),
		UpdatedAt:         booking.UpdatedAt.UnixMilli(),
	}

	// Convert payment to BundlePayment
	bundlePayment := models.BundlePayment{
		ID:            payment.ID,
		BookingID:     payment.BookingID,
		Amount:        payment.Amount,
		PaymentMethod: payment.PaymentMethod,
		Status:        payment.Status,
		TransactionID: payment.TransactionID,
		PaymentData:   payment.PaymentData,
		CreatedAt:     payment.CreatedAt.UnixMilli(),
		UpdatedAt:     payment.UpdatedAt.UnixMilli(),
	}

	// Convert tickets to BundleTickets
	bundleTickets := make([]models.BundleTicket, len(tickets))
	for i, ticket := range tickets {
		bundleTicket := models.BundleTicket{
			ID:                  ticket.ID,
			BookingID:           ticket.BookingID,
			TicketNumber:        ticket.TicketNumber,
			QRCode:              ticket.QRCode,
			IsUsed:              ticket.IsUsed,
			ValidatedBy:         ticket.ValidatedBy,
			CreatedAt:           ticket.CreatedAt.UnixMilli(),
			UpdatedAt:           ticket.UpdatedAt.UnixMilli(),
			PickupLocationName:  ticket.PickupLocationName,
			DropoffLocationName: ticket.DropoffLocationName,
			CarPlate:            ticket.CarPlate,
			CarCompany:          ticket.CarCompany,
			PickupTime:          ticket.PickupTime.UnixMilli(),
		}

		// Handle UsedAt field
		if ticket.UsedAt != nil {
			usedAt := ticket.UsedAt.UnixMilli()
			bundleTicket.UsedAt = &usedAt
		}

		bundleTickets[i] = bundleTicket
	}

	// Create bundle
	bundle := &models.BookingBundle{
		TripID:  fmt.Sprintf("%d", booking.TripID),
		Booking: tripBooking,
		Payment: bundlePayment,
		Tickets: bundleTickets,
	}

	fmt.Printf("[CreateBookingBundle] Bundle created successfully for trip %s with %d tickets\n",
		bundle.TripID, len(bundle.Tickets))

	return bundle, nil
}

// SetFromRabbitMQ sets the flag to indicate if booking came from RabbitMQ
func (s *bookingService) SetFromRabbitMQ(fromRabbitMQ bool) {
	prev := s.fromRabbitMQ
	s.fromRabbitMQ = fromRabbitMQ
	fmt.Printf("[BookingService] SetFromRabbitMQ changed: %t -> %t\n", prev, s.fromRabbitMQ)
}
