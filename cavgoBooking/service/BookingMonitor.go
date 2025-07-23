package service

import (
	"context"
	"fmt"
	"time"

	"cavgoBooking/repository"
)

// StartBookingMonitor starts a background goroutine that checks for expired pending bookings every minute.
func StartBookingMonitor(repo repository.BookingRepository, publisher *RabbitMQPublisher) {
	go func() {
		ticker := time.NewTicker(1 * time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				ctx := context.Background()
				cutoff := time.Now().Add(-5 * time.Minute)
				bookings, err := repo.GetExpiredPendingBookings(ctx, cutoff)
				if err != nil {
					fmt.Println("[BookingMonitor] Error fetching expired bookings:", err)
					continue
				}
				for _, booking := range bookings {
					err := repo.CancelBookingAndFailPayment(ctx, booking.ID)
					if err != nil {
						fmt.Printf("[BookingMonitor] Failed to cancel booking %s: %v\n", booking.ID, err)
						continue
					}
					// Publish cancellation event
					if publisher != nil {
						resp := map[string]interface{}{
							"booking_id": booking.ID,
							"status":     "CANCELED",
							"message":    "Booking auto-canceled after 5 minutes of pending payment.",
						}
						err := publisher.PublishBookingEvent("canceled", resp)
						if err != nil {
							fmt.Printf("[BookingMonitor] Failed to publish cancellation for booking %s: %v\n", booking.ID, err)
						}
					}
				}
			}
		}
	}()
}
