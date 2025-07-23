package repository

import (
	"context"
	"time"

	"cavgoBooking/models"

	"github.com/jmoiron/sqlx"
)

type BookingRepository interface {
	CreateBooking(ctx context.Context, booking *models.Booking) error
	GetBookingByID(ctx context.Context, id string) (*models.Booking, error)
	GetBookingByReference(ctx context.Context, reference string) (*models.Booking, error)
	GetBookingsByTripID(ctx context.Context, tripID int) ([]models.Booking, error)
	GetBookingsByUserID(ctx context.Context, userID string) ([]models.Booking, error)
	UpdateBookingStatus(ctx context.Context, id string, status models.BookingStatus) error

	CreateTickets(ctx context.Context, tickets []models.Ticket) error
	GetTicketsByBookingID(ctx context.Context, bookingID string) ([]models.Ticket, error)
	GetTicketByNumber(ctx context.Context, ticketNumber string) (*models.Ticket, error)
	GetTicketByQRCode(ctx context.Context, qrCode string) (*models.Ticket, error)
	ValidateTicket(ctx context.Context, ticketID string, validatedBy string) error

	CreatePayment(ctx context.Context, payment *models.Payment) error
	GetPaymentByBookingID(ctx context.Context, bookingID string) (*models.Payment, error)
	UpdatePaymentStatus(ctx context.Context, id string, status models.PaymentStatus, transactionID *string) error

	// New methods for booking monitor
	// Fetch bookings with pending payment older than 5 minutes
	GetExpiredPendingBookings(ctx context.Context, olderThan time.Time) ([]models.Booking, error)
	// Atomically cancel booking and fail payment
	CancelBookingAndFailPayment(ctx context.Context, bookingID string) error
}

type bookingRepository struct {
	db *sqlx.DB
}

func NewBookingRepository(db *sqlx.DB) BookingRepository {
	return &bookingRepository{db: db}
}

func (r *bookingRepository) CreateBooking(ctx context.Context, booking *models.Booking) error {
	query := `
		INSERT INTO bookings (id, trip_id, user_id, user_email, user_phone, user_name, 
					     pickup_location_id, dropoff_location_id, number_of_tickets, 
					     total_amount, status, booking_reference, created_at, updated_at)
		VALUES (:id, :trip_id, :user_id, :user_email, :user_phone, :user_name, 
			:pickup_location_id, :dropoff_location_id, :number_of_tickets, 
			:total_amount, :status, :booking_reference, :created_at, :updated_at)`

	_, err := r.db.NamedExecContext(ctx, query, booking)
	return err
}

func (r *bookingRepository) GetBookingByID(ctx context.Context, id string) (*models.Booking, error) {
	query := `SELECT * FROM bookings WHERE id = $1`

	var booking models.Booking
	err := r.db.GetContext(ctx, &booking, query, id)
	if err != nil {
		return nil, err
	}

	return &booking, nil
}

func (r *bookingRepository) GetBookingByReference(ctx context.Context, reference string) (*models.Booking, error) {
	query := `SELECT * FROM bookings WHERE booking_reference = $1`

	var booking models.Booking
	err := r.db.GetContext(ctx, &booking, query, reference)
	if err != nil {
		return nil, err
	}

	return &booking, nil
}

func (r *bookingRepository) GetBookingsByTripID(ctx context.Context, tripID int) ([]models.Booking, error) {
	query := `SELECT * FROM bookings WHERE trip_id = $1 ORDER BY created_at DESC`

	var bookings []models.Booking
	err := r.db.SelectContext(ctx, &bookings, query, tripID)
	if err != nil {
		return nil, err
	}

	return bookings, nil
}

func (r *bookingRepository) GetBookingsByUserID(ctx context.Context, userID string) ([]models.Booking, error) {
	query := `SELECT * FROM bookings WHERE user_id = $1 ORDER BY created_at DESC`

	var bookings []models.Booking
	err := r.db.SelectContext(ctx, &bookings, query, userID)
	if err != nil {
		return nil, err
	}

	return bookings, nil
}

func (r *bookingRepository) UpdateBookingStatus(ctx context.Context, id string, status models.BookingStatus) error {
	query := `UPDATE bookings SET status = $1, updated_at = $2 WHERE id = $3`

	_, err := r.db.ExecContext(ctx, query, status, time.Now(), id)
	return err
}

func (r *bookingRepository) CreateTickets(ctx context.Context, tickets []models.Ticket) error {
	query := `
		INSERT INTO tickets (id, booking_id, ticket_number, qr_code, is_used, created_at, updated_at,
		                   pickup_location_name, dropoff_location_name, car_plate, car_company, pickup_time)
		VALUES (:id, :booking_id, :ticket_number, :qr_code, :is_used, :created_at, :updated_at,
		        :pickup_location_name, :dropoff_location_name, :car_plate, :car_company, :pickup_time)`

	_, err := r.db.NamedExecContext(ctx, query, tickets)
	return err
}

func (r *bookingRepository) GetTicketsByBookingID(ctx context.Context, bookingID string) ([]models.Ticket, error) {
	query := `SELECT * FROM tickets WHERE booking_id = $1 ORDER BY created_at`

	var tickets []models.Ticket
	err := r.db.SelectContext(ctx, &tickets, query, bookingID)
	if err != nil {
		return nil, err
	}

	return tickets, nil
}

func (r *bookingRepository) GetTicketByNumber(ctx context.Context, ticketNumber string) (*models.Ticket, error) {
	query := `SELECT * FROM tickets WHERE ticket_number = $1`

	var ticket models.Ticket
	err := r.db.GetContext(ctx, &ticket, query, ticketNumber)
	if err != nil {
		return nil, err
	}

	return &ticket, nil
}

func (r *bookingRepository) GetTicketByQRCode(ctx context.Context, qrCode string) (*models.Ticket, error) {
	query := `SELECT * FROM tickets WHERE qr_code = $1`

	var ticket models.Ticket
	err := r.db.GetContext(ctx, &ticket, query, qrCode)
	if err != nil {
		return nil, err
	}

	return &ticket, nil
}

func (r *bookingRepository) ValidateTicket(ctx context.Context, ticketID string, validatedBy string) error {
	query := `UPDATE tickets SET is_used = true, used_at = $1, validated_by = $2, updated_at = $3 WHERE id = $4`

	now := time.Now()
	_, err := r.db.ExecContext(ctx, query, now, validatedBy, now, ticketID)
	return err
}

func (r *bookingRepository) CreatePayment(ctx context.Context, payment *models.Payment) error {
	query := `
		INSERT INTO payments (id, booking_id, amount, payment_method, status, transaction_id, payment_data, created_at, updated_at)
		VALUES (:id, :booking_id, :amount, :payment_method, :status, :transaction_id, :payment_data, :created_at, :updated_at)`

	_, err := r.db.NamedExecContext(ctx, query, payment)
	return err
}

func (r *bookingRepository) GetPaymentByBookingID(ctx context.Context, bookingID string) (*models.Payment, error) {
	query := `SELECT * FROM payments WHERE booking_id = $1`

	var payment models.Payment
	err := r.db.GetContext(ctx, &payment, query, bookingID)
	if err != nil {
		return nil, err
	}

	return &payment, nil
}

func (r *bookingRepository) UpdatePaymentStatus(ctx context.Context, id string, status models.PaymentStatus, transactionID *string) error {
	query := `UPDATE payments SET status = $1, transaction_id = $2, updated_at = $3 WHERE id = $4`

	_, err := r.db.ExecContext(ctx, query, status, transactionID, time.Now(), id)
	return err
}

// Implementation for fetching expired pending bookings
func (r *bookingRepository) GetExpiredPendingBookings(ctx context.Context, olderThan time.Time) ([]models.Booking, error) {
	query := `SELECT * FROM bookings WHERE status = 'PENDING' AND created_at < $1`
	var bookings []models.Booking
	err := r.db.SelectContext(ctx, &bookings, query, olderThan)
	if err != nil {
		return nil, err
	}
	// Filter by payment status in Go (since payment is in another table)
	var result []models.Booking
	for _, booking := range bookings {
		payment, err := r.GetPaymentByBookingID(ctx, booking.ID)
		if err == nil && payment.Status == models.PaymentStatusPending {
			result = append(result, booking)
		}
	}
	return result, nil
}

// Implementation for atomically cancelling booking and failing payment
func (r *bookingRepository) CancelBookingAndFailPayment(ctx context.Context, bookingID string) error {
	tx, err := r.db.BeginTxx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() {
		if p := recover(); p != nil {
			tx.Rollback()
			panic(p)
		} else if err != nil {
			tx.Rollback()
		} else {
			err = tx.Commit()
		}
	}()

	// Update booking status
	_, err = tx.ExecContext(ctx, `UPDATE bookings SET status = $1, updated_at = $2 WHERE id = $3`, models.BookingStatusCanceled, time.Now(), bookingID)
	if err != nil {
		return err
	}
	// Update payment status
	_, err = tx.ExecContext(ctx, `UPDATE payments SET status = $1, updated_at = $2 WHERE booking_id = $3`, models.PaymentStatusFailed, time.Now(), bookingID)
	if err != nil {
		return err
	}
	return nil
}
