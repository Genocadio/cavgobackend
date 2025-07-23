package com.nexxserve.cavgomqt.service;

import com.nexxserve.cavgomqt.dto.BookingEventMessage;
import com.nexxserve.cavgomqt.dto.BookingResponse;
import com.nexxserve.cavgomqt.dto.Trip;
import com.nexxserve.cavgomqt.dto.TripEventMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RabbitMQListenerService {



    @Autowired
    private MqttService mqttService;
    private static final Logger logger = LoggerFactory.getLogger(RabbitMQListenerService.class);

    @RabbitListener(queues = "bookings.queue")
    public void handleBookingChanges(BookingEventMessage message) {
        logger.info("Received booking message: {}", message.toString());

        try {
//            BookingEventMessage eventMessage = objectMapper.readValue(message, BookingEventMessage.class);
//            BookingResponse bookingResponse = message.getData();
            mqttService.publishBooking(message);


            logger.info("Event: {}", message.getEvent());
//
//            logger.info("Processed booking change:");
//            logger.info("- Booking ID: {}", bookingResponse.getBooking().getId());
//            logger.info("- Trip ID: {}", bookingResponse.getBooking().getTripId());
//            logger.info("- User: {} ({})", bookingResponse.getBooking().getUserName(), bookingResponse.getBooking().getUserPhone());
//            logger.info("- Status: {}", bookingResponse.getBooking().getStatus());
//            logger.info("- Tickets: {}", bookingResponse.getBooking().getNumberOfTickets());
//            logger.info("- Total Amount: {}", bookingResponse.getBooking().getTotalAmount());
//            logger.info("- Message: {}", bookingResponse.getMessage());
//            logger.info("- Payment Reference: {}", bookingResponse.getPaymentReference());
//
//            if (bookingResponse.getBooking().getPayment() != null) {
//                logger.info("- Payment Status: {}", bookingResponse.getBooking().getPayment().getStatus());
//                logger.info("- Payment Method: {}", bookingResponse.getBooking().getPayment().getPaymentMethod());
//            }

        } catch (Exception e) {
            logger.error("Error parsing booking message: {}", e.getMessage());
        }
    }

    @RabbitListener(queues = "trips.queue")
    public void handleTripChanges(TripEventMessage message) {
        logger.info("Received trip message: {}", message);

        try {
            Trip trip = message.getData();
            mqttService.publishTrip(message);

//            logger.info("Processed trip change:");
//            logger.info("- Trip ID: {}", trip.getId());
//            logger.info("- Status: {}", trip.getStatus());
//            logger.info("- Vehicle: {} ({})", trip.getVehicle().getLicensePlate(), trip.getVehicle().getCompanyName());
//            logger.info("- Driver: {} ({})", trip.getVehicle().getDriver().getName(), trip.getVehicle().getDriver().getPhone());
//            logger.info("- Departure Time: {}", trip.getDepartureTime());
//            logger.info("- Seats: {}", trip.getSeats());
//            logger.info("- Connection Mode: {}", trip.getConnectionMode());
//
//
//            if (trip.getRoute() != null) {
//                logger.info("- Route: {} -> {}",
//                        trip.getRoute().getOrigin().getCustomName() != null ?
//                                trip.getRoute().getOrigin().getCustomName() : trip.getRoute().getOrigin().getGooglePlaceName(),
//                        trip.getRoute().getDestination().getCustomName() != null ?
//                                trip.getRoute().getDestination().getCustomName() : trip.getRoute().getDestination().getGooglePlaceName());
//                logger.info("- Route Price: {}", trip.getRoute().getRoutePrice());
//            }
//
//            if (trip.getCurrentLatitude() != null && trip.getCurrentLongitude() != null) {
//                logger.info("- Current Location: {}, {}", trip.getCurrentLatitude(), trip.getCurrentLongitude());
//            }
//
//            if (trip.getCurrentSpeed() != null) {
//                logger.info("- Current Speed: {} km/h", trip.getCurrentSpeed());
//            }
//
//            if (trip.getRemainingTimeToDestination() != null) {
//                logger.info("- Remaining Time: {} seconds", trip.getRemainingTimeToDestination());
//            }
//
//            if (trip.getWaypoints() != null && !trip.getWaypoints().isEmpty()) {
//                logger.info("- Waypoints: {} total", trip.getWaypoints().size());
//                trip.getWaypoints().forEach(waypoint -> {
//                    logger.info("  - Waypoint {}: {} (passed: {}, next: {})",
//                            waypoint.getOrder(),
//                            waypoint.getLocation().getCustomName() != null ?
//                                    waypoint.getLocation().getCustomName() : waypoint.getLocation().getGooglePlaceName(),
//                            waypoint.getIsPassed(),
//                            waypoint.getIsNext());
//                });
//            }

        } catch (Exception e) {
            logger.error("Error parsing trip message: {}", e.getMessage());
        }
    }
}