package models

import (
	"time"
)

// BookingStatus represents the status of a booking
type BookingStatus string

const (
	BookingStatusPending   BookingStatus = "PENDING"
	BookingStatusConfirmed BookingStatus = "CONFIRMED"
	BookingStatusCanceled  BookingStatus = "CANCELED"
	BookingStatusUsed      BookingStatus = "USED"
	BookingStatusExpired   BookingStatus = "EXPIRED"
)

// Booking represents a ticket booking
type Booking struct {
	ID                string        `json:"id" db:"id"`
	TripID            int           `json:"trip_id" db:"trip_id"`
	UserID            *string       `json:"user_id,omitempty" db:"user_id"`
	UserEmail         *string       `json:"user_email,omitempty" db:"user_email"`
	UserPhone         string        `json:"user_phone" db:"user_phone"`
	UserName          string        `json:"user_name" db:"user_name"`
	PickupLocationID  string        `json:"pickup_location_id" db:"pickup_location_id"`
	DropoffLocationID string        `json:"dropoff_location_id" db:"dropoff_location_id"`
	NumberOfTickets   int           `json:"number_of_tickets" db:"number_of_tickets"`
	TotalAmount       float64       `json:"total_amount" db:"total_amount"`
	Status            BookingStatus `json:"status" db:"status"`
	BookingReference  string        `json:"booking_reference" db:"booking_reference"`
	CreatedAt         time.Time     `json:"created_at" db:"created_at"`
	UpdatedAt         time.Time     `json:"updated_at" db:"updated_at"`

	// Relations
	Tickets []Ticket `json:"tickets,omitempty"`
	Payment *Payment `json:"payment,omitempty"`
}

// BookingRequest represents the request to create a booking
type BookingRequest struct {
	TripID            int     `json:"trip_id" validate:"required"`
	PickupLocationID  string  `json:"pickup_location_id" validate:"required"`
	DropoffLocationID string  `json:"dropoff_location_id" validate:"required"`
	NumberOfTickets   int     `json:"number_of_tickets" validate:"required,min=1"`
	TotalAmount       float64 `json:"total_amount" validate:"required,min=0"`

	// User info
	UserID    *string `json:"user_id,omitempty"`
	UserEmail *string `json:"user_email,omitempty"`
	UserPhone string  `json:"user_phone" validate:"required"`
	UserName  string  `json:"user_name" validate:"required"`

	// Payment info
	PaymentMethod PaymentMethod `json:"payment_method" validate:"required"`
	PaymentData   *string       `json:"payment_data,omitempty"`
}

// TicketValidationRequest represents ticket validation request
type TicketValidationRequest struct {
	TicketNumber string `json:"ticket_number" validate:"required"`
	QRCode       string `json:"qr_code" validate:"required"`
	ValidatedBy  string `json:"validated_by" validate:"required"`
}

// BookingResponse represents the response after creating a booking
type BookingResponse struct {
	Booking          *Booking `json:"booking"`
	Message          string   `json:"message"`
	PaymentReference *string  `json:"payment_reference,omitempty"`
}
