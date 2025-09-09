package models

// TripEventMessage is used for RabbitMQ trip events (created/updated)
type TripEventMessage struct {
	Event string `json:"event"` // "created" or "updated"
	Data  Trip   `json:"data"`
}

// Booking represents the booking data structure from booking events
// (fields based on provided JSON)
type Booking struct {
	ID                string   `json:"id"`
	TripID            int64    `json:"trip_id"`
	UserID            string   `json:"user_id"`
	UserEmail         string   `json:"user_email"`
	UserPhone         string   `json:"user_phone"`
	UserName          string   `json:"user_name"`
	PickupLocationID  string   `json:"pickup_location_id"`
	DropoffLocationID string   `json:"dropoff_location_id"`
	NumberOfTickets   int      `json:"number_of_tickets"`
	TotalAmount       float64  `json:"total_amount"`
	Status            string   `json:"status"`
	BookingReference  string   `json:"booking_reference"`
	CreatedAt         string   `json:"created_at"`
	UpdatedAt         string   `json:"updated_at"`
	Tickets           []Ticket `json:"tickets"`
	Payment           Payment  `json:"payment"`
}

type Ticket struct {
	ID                  string `json:"id"`
	BookingID           string `json:"booking_id"`
	TicketNumber        string `json:"ticket_number"`
	QRCode              string `json:"qr_code"`
	IsUsed              bool   `json:"is_used"`
	CreatedAt           string `json:"created_at"`
	UpdatedAt           string `json:"updated_at"`
	PickupLocationName  string `json:"pickup_location_name"`
	DropoffLocationName string `json:"dropoff_location_name"`
	CarPlate            string `json:"car_plate"`
	CarCompany          string `json:"car_company"`
	PickupTime          string `json:"pickup_time"`
}

type Payment struct {
	ID            string  `json:"id"`
	BookingID     string  `json:"booking_id"`
	Amount        float64 `json:"amount"`
	PaymentMethod string  `json:"payment_method"`
	Status        string  `json:"status"`
	TransactionID string  `json:"transaction_id"`
	PaymentData   string  `json:"payment_data"`
	CreatedAt     string  `json:"created_at"`
	UpdatedAt     string  `json:"updated_at"`
}

// BookingEventData matches the actual JSON structure: {"data": {"booking": ...}, ...}
type BookingEventData struct {
	Booking Booking `json:"booking"`
}

// BookingEventMessage is used for RabbitMQ booking events
type BookingEventMessage struct {
	Event            string           `json:"event"`
	Data             BookingEventData `json:"data"`
	Message          string           `json:"message"`
	PaymentReference string           `json:"payment_reference"`
}

// MQTTTripEventMessage is used for RabbitMQ trip events from MQTT service
// This matches the Java TripEventMessage structure
type MQTTTripEventMessage struct {
	Event string `json:"event"` // TRIP_STARTED, TRIP_COMPLETED, TRIP_CANCELLED, TRIP_UPDATED
	Data  Trip   `json:"data"`  // Trip object with updated data
}