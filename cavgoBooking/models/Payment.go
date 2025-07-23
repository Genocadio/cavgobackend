package models

import (
	"time"
)

// PaymentStatus represents the payment status
type PaymentStatus string

const (
	PaymentStatusPending   PaymentStatus = "PENDING"
	PaymentStatusCompleted PaymentStatus = "COMPLETED"
	PaymentStatusFailed    PaymentStatus = "FAILED"
	PaymentStatusRefunded  PaymentStatus = "REFUNDED"
)

// PaymentMethod represents the payment method
type PaymentMethod string

const (
	PaymentMethodCash         PaymentMethod = "CASH"
	PaymentMethodCard         PaymentMethod = "CARD"
	PaymentMethodMobileMoney  PaymentMethod = "MOBILE_MONEY"
	PaymentMethodBankTransfer PaymentMethod = "BANK_TRANSFER"
)

// Ticket represents individual tickets within a booking
type Ticket struct {
	ID                  string     `json:"id" db:"id"`
	BookingID           string     `json:"booking_id" db:"booking_id"`
	TicketNumber        string     `json:"ticket_number" db:"ticket_number"`
	QRCode              string     `json:"qr_code" db:"qr_code"`
	IsUsed              bool       `json:"is_used" db:"is_used"`
	UsedAt              *time.Time `json:"used_at,omitempty" db:"used_at"`
	ValidatedBy         *string    `json:"validated_by,omitempty" db:"validated_by"`
	CreatedAt           time.Time  `json:"created_at" db:"created_at"`
	UpdatedAt           time.Time  `json:"updated_at" db:"updated_at"`
	PickupLocationName  string     `json:"pickup_location_name" db:"pickup_location_name"`
	DropoffLocationName string     `json:"dropoff_location_name" db:"dropoff_location_name"`
	CarPlate            string     `json:"car_plate" db:"car_plate"`
	CarCompany          string     `json:"car_company" db:"car_company"`
	PickupTime          time.Time  `json:"pickup_time" db:"pickup_time"`
}

// Payment represents payment information
type Payment struct {
	ID            string        `json:"id" db:"id"`
	BookingID     string        `json:"booking_id" db:"booking_id"`
	Amount        float64       `json:"amount" db:"amount"`
	PaymentMethod PaymentMethod `json:"payment_method" db:"payment_method"`
	Status        PaymentStatus `json:"status" db:"status"`
	TransactionID *string       `json:"transaction_id,omitempty" db:"transaction_id"`
	PaymentData   *string       `json:"payment_data,omitempty" db:"payment_data"` // JSON field for additional payment info
	CreatedAt     time.Time     `json:"created_at" db:"created_at"`
	UpdatedAt     time.Time     `json:"updated_at" db:"updated_at"`
}
