package handlers

import (
	"encoding/json"
	"net/http"
	"strconv"

	"cavgoBooking/models"
	"cavgoBooking/service"

	"github.com/gorilla/mux"
)

type BookingHandler struct {
	bookingService service.BookingService
}

func NewBookingHandler(bookingService service.BookingService) *BookingHandler {
	return &BookingHandler{
		bookingService: bookingService,
	}
}

// CreateBooking handles POST /bookings
func (h *BookingHandler) CreateBooking(w http.ResponseWriter, r *http.Request) {
	var req models.BookingRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	response, err := h.bookingService.CreateBooking(r.Context(), &req)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(response)
}

// GetBookingByID handles GET /bookings/{id}
func (h *BookingHandler) GetBookingByID(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id := vars["id"]

	booking, err := h.bookingService.GetBookingByID(r.Context(), id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(booking)
}

// GetBookingByReference handles GET /bookings/reference/{reference}
func (h *BookingHandler) GetBookingByReference(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	reference := vars["reference"]

	booking, err := h.bookingService.GetBookingByReference(r.Context(), reference)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(booking)
}

// GetBookingsByTripID handles GET /bookings/trip/{tripId}
func (h *BookingHandler) GetBookingsByTripID(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	tripIDStr := vars["tripId"]

	tripID, err := strconv.Atoi(tripIDStr)
	if err != nil {
		http.Error(w, "Invalid trip ID", http.StatusBadRequest)
		return
	}

	bookings, err := h.bookingService.GetBookingsByTripID(r.Context(), tripID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(bookings)
}

// GetBookingsByUserID handles GET /bookings/user/{userId}
func (h *BookingHandler) GetBookingsByUserID(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	userID := vars["userId"]

	bookings, err := h.bookingService.GetBookingsByUserID(r.Context(), userID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(bookings)
}

// CancelBooking handles DELETE /bookings/{id}
func (h *BookingHandler) CancelBooking(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id := vars["id"]

	err := h.bookingService.CancelBooking(r.Context(), id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

// ValidateTicket handles POST /tickets/validate
func (h *BookingHandler) ValidateTicket(w http.ResponseWriter, r *http.Request) {
	var req models.TicketValidationRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	ticket, err := h.bookingService.ValidateTicket(r.Context(), &req)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"ticket":  ticket,
		"message": "Ticket validated successfully",
	})
}

// GetTicketsByBookingID handles GET /bookings/{id}/tickets
func (h *BookingHandler) GetTicketsByBookingID(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	bookingID := vars["id"]

	tickets, err := h.bookingService.GetTicketsByBookingID(r.Context(), bookingID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(tickets)
}

// ProcessPayment handles POST /bookings/{id}/payment
func (h *BookingHandler) ProcessPayment(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	bookingID := vars["id"]

	response, err := h.bookingService.ProcessPayment(r.Context(), bookingID, nil)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(response)
}

// RefundPayment handles POST /bookings/{id}/refund
func (h *BookingHandler) RefundPayment(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	bookingID := vars["id"]

	err := h.bookingService.RefundPayment(r.Context(), bookingID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{
		"message": "Payment refunded successfully",
	})
}

// RegisterRoutes registers all booking routes
func (h *BookingHandler) RegisterRoutes(r *mux.Router) {
	// Booking routes
	r.HandleFunc("/bookings", h.CreateBooking).Methods("POST")
	r.HandleFunc("/bookings/{id}", h.GetBookingByID).Methods("GET")
	r.HandleFunc("/bookings/reference/{reference}", h.GetBookingByReference).Methods("GET")
	r.HandleFunc("/bookings/trip/{tripId}", h.GetBookingsByTripID).Methods("GET")
	r.HandleFunc("/bookings/user/{userId}", h.GetBookingsByUserID).Methods("GET")
	r.HandleFunc("/bookings/{id}", h.CancelBooking).Methods("DELETE")

	// Ticket routes
	r.HandleFunc("/tickets/validate", h.ValidateTicket).Methods("POST")
	r.HandleFunc("/bookings/{id}/tickets", h.GetTicketsByBookingID).Methods("GET")

	// Payment routes
	r.HandleFunc("/bookings/{id}/payment", h.ProcessPayment).Methods("POST")
	r.HandleFunc("/bookings/{id}/refund", h.RefundPayment).Methods("POST")
}
