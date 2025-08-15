package com.gocavgo.ussdservice.service;

import com.gocavgo.ussdservice.dto.LocationDto;
import com.gocavgo.ussdservice.dto.TripWaypointDto;
import com.gocavgo.ussdservice.dto.TripBookingOption;
import com.gocavgo.ussdservice.dto.MatchedLocation;
import com.gocavgo.ussdservice.dto.USSDRequest;
import com.gocavgo.ussdservice.dto.TripDto;
import com.gocavgo.ussdservice.entity.UserSession;
import com.gocavgo.ussdservice.repository.UserSessionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;


@Service
@RequiredArgsConstructor
@Slf4j
public class USSDService {

    private final UserSessionRepository userSessionRepository;
    private final LanguageService languageService;
    private final TripService tripService;

    @Transactional
    public String processUSSDRequest(USSDRequest request) {
        log.info("Processing USSD request from: {}, text: '{}'", request.getPhoneNumber(), request.getText());

        UserSession userSession = getUserSession(request.getPhoneNumber());

        // Update session ID
        userSession.setSessionId(request.getSessionId());

        String response;

        if (request.getText() == null || request.getText().isEmpty()) {
            // First time user or session start
            if (userSession.getLanguage() == null) {
                response = handleFirstTimeUser(userSession);
            } else {
                response = showBookNowMenu(userSession);
            }
        } else {
            response = handleUserInput(userSession, request.getText());
        }

        userSessionRepository.save(userSession);
        log.info("Response to {}: {}", request.getPhoneNumber(), response);
        return response;
    }

    private UserSession getUserSession(String phoneNumber) {
        return userSessionRepository.findByPhoneNumber(phoneNumber)
                .orElse(UserSession.builder()
                        .phoneNumber(phoneNumber)
                        .currentStep("welcome")
                        .build());
    }

    private String handleFirstTimeUser(UserSession userSession) {
        userSession.setCurrentStep("language_selection");
        return languageService.getMessage("en", "welcome");
    }

    private String showBookNowMenu(UserSession userSession) {
        userSession.setCurrentStep("book_now");
        return languageService.getMessage(userSession.getLanguage(), "book_now");
    }

    private String handleUserInput(UserSession userSession, String text) {
        // If language is not set, force language selection regardless of input
        if (userSession.getLanguage() == null) {
            String[] inputs = text.split("\\*");
            String currentInput = inputs[inputs.length - 1];
            if (!currentInput.matches("[123]")) {
                userSession.setCurrentStep("language_selection");
                return languageService.getMessage("en", "welcome");
            }
            // Proceed to handle language selection
            userSession.setCurrentStep("language_selection");
            return handleLanguageSelection(userSession, currentInput);
        }

        String[] inputs = text.split("\\*");
        String currentInput = inputs[inputs.length - 1];
        log.debug("Input array: {}, Current input: '{}'", java.util.Arrays.toString(inputs), currentInput);

        String currentStep = userSession.getCurrentStep();
        String language = userSession.getLanguage();

        return switch (currentStep) {
            case "language_selection" -> handleLanguageSelection(userSession, currentInput);
            case "book_now" -> handleBookNowSelection(userSession, currentInput, inputs);
            case "enter_origin" -> handleOriginInput(userSession, currentInput);
            case "enter_destination" -> handleDestinationInput(userSession, currentInput);
            case "trip_selection" -> handleTripSelection(userSession, currentInput);
            default -> languageService.getMessage(language, "invalid_option") + "\n" +
                    languageService.getMessage(language, "book_now");
        };
    }

    private String handleLanguageSelection(UserSession userSession, String input) {
        if (input.matches("[123]")) {
            String selectedLanguage = languageService.getLanguageFromChoice(input);
            userSession.setLanguage(selectedLanguage);
            userSession.setCurrentStep("book_now");
            return languageService.getMessage(selectedLanguage, "book_now");
        } else {
            return languageService.getMessage("en", "invalid_option") + "\n" +
                    languageService.getMessage("en", "welcome");
        }
    }

    private String handleBookNowSelection(UserSession userSession, String input, String[] allInputs) {
        String language = userSession.getLanguage();

        // Handle shortcut input: 1*origin*destination or 1*origin
        if (allInputs.length >= 2 && "1".equals(allInputs[0])) {
            String origin = allInputs[1];

            // Validate origin
            if (origin == null || origin.trim().isEmpty()) {
                userSession.setCurrentStep("enter_origin");
                return languageService.getMessage(language, "invalid_origin") + "\n" +
                        languageService.getMessage(language, "enter_origin");
            }

            userSession.setOrigin(origin.trim());

            // Check if destination is also provided: 1*origin*destination
            if (allInputs.length >= 3) {
                String destination = allInputs[2];

                if (destination == null || destination.trim().isEmpty()) {
                    userSession.setCurrentStep("enter_destination");
                    return languageService.getMessage(language, "invalid_destination") + "\n" +
                            languageService.getMessage(language, "enter_destination");
                }

                userSession.setDestination(destination.trim());

                // Search for trips directly
                try {
                    List<TripDto> trips = tripService.getTripsByRoute(
                            userSession.getOrigin(),
                            userSession.getDestination(),
                            5, // limit to 5 trips for USSD display
                            0
                    );

                    if (trips.isEmpty()) {
                        userSession.setCurrentStep("book_now");
                        return languageService.getMessage(language, "no_trips_found") + "\n" +
                                languageService.getMessage(language, "book_now");
                    }

                    // Store trips in session for selection
                    userSession.setAvailableTrips(trips);
                    userSession.setCurrentStep("trip_selection");

                    return formatTripsList(trips, language,
                            userSession.getOrigin(), userSession.getDestination(), userSession);

                } catch (Exception e) {
                    log.error("Error searching for trips", e);
                    userSession.setCurrentStep("book_now");
                    return languageService.getMessage(language, "search_error") + "\n" +
                            languageService.getMessage(language, "book_now");
                }
            } else {
                // Only origin provided: 1*origin
                userSession.setCurrentStep("enter_destination");
                return languageService.getMessage(language, "enter_destination");
            }
        }

        // Handle different navigation scenarios for single inputs
        if (allInputs.length == 1) {
            // Direct selection from main menu (e.g., "1", "2", "3")
            return switch (input) {
                case "1" -> { // Book Trip
                    userSession.setCurrentStep("enter_origin");
                    yield languageService.getMessage(language, "enter_origin");
                }
                case "2" -> { // Change Language
                    userSession.setCurrentStep("language_selection");
                    yield languageService.getMessage(language, "welcome");
                }
                case "3" -> // Help
                        languageService.getMessage(language, "help");
                default ->
                        languageService.getMessage(language, "invalid_option") + "\n" +
                                languageService.getMessage(language, "book_now");
            };
        } else if (allInputs.length == 2) {
            // Navigation from main menu selection (e.g., "1*2" for first-time users after language selection)
            String firstChoice = allInputs[0];
            String secondChoice = allInputs[1];

            if ("1".equals(firstChoice) && "2".equals(secondChoice)) {
                // First time user: language selected (1), then change language (2)
                userSession.setCurrentStep("language_selection");
                return languageService.getMessage(language, "welcome");
            } else if ("2".equals(firstChoice) && "1".equals(secondChoice)) {
                // User chose change language (2), then selected a language (1)
                String selectedLanguage = languageService.getLanguageFromChoice(secondChoice);
                userSession.setLanguage(selectedLanguage);
                userSession.setCurrentStep("book_now");
                return languageService.getMessage(selectedLanguage, "book_now");
            }
        }

        // Default handling for current input
        return switch (input) {
            case "1" -> { // Book Trip
                userSession.setCurrentStep("enter_origin");
                yield languageService.getMessage(language, "enter_origin");
            }
            case "2" -> { // Change Language
                userSession.setCurrentStep("language_selection");
                yield languageService.getMessage(language, "welcome");
            }
            case "3" -> // Help
                    languageService.getMessage(language, "help");
            default ->
                    languageService.getMessage(language, "invalid_option") + "\n" +
                            languageService.getMessage(language, "book_now");
        };
    }

    private String handleOriginInput(UserSession userSession, String input) {
        String language = userSession.getLanguage();

        if (input == null || input.trim().isEmpty()) {
            return languageService.getMessage(language, "invalid_origin") + "\n" +
                    languageService.getMessage(language, "enter_origin");
        }

        userSession.setOrigin(input.trim());
        userSession.setCurrentStep("enter_destination");
        return languageService.getMessage(language, "enter_destination");
    }

    private String handleDestinationInput(UserSession userSession, String input) {
        String language = userSession.getLanguage();

        if (input == null || input.trim().isEmpty()) {
            return languageService.getMessage(language, "invalid_destination") + "\n" +
                    languageService.getMessage(language, "enter_destination");
        }

        userSession.setDestination(input.trim());

        // Search for trips
        try {
            List<TripDto> trips = tripService.getTripsByRoute(
                    userSession.getOrigin(),
                    userSession.getDestination(),
                    5, // limit to 5 trips for USSD display
                    0
            );

            if (trips.isEmpty()) {
                userSession.setCurrentStep("book_now");
                return languageService.getMessage(language, "no_trips_found") + "\n" +
                        languageService.getMessage(language, "book_now");
            }

            // Store trips in session for selection
            userSession.setAvailableTrips(trips);
            userSession.setCurrentStep("trip_selection");

            return formatTripsList(trips, language,
                    userSession.getOrigin(), userSession.getDestination(), userSession);

        } catch (Exception e) {
            log.error("Error searching for trips", e);
            userSession.setCurrentStep("book_now");
            return languageService.getMessage(language, "search_error") + "\n" +
                    languageService.getMessage(language, "book_now");
        }
    }

    private String handleTripSelection(UserSession userSession, String input) {
        String language = userSession.getLanguage();

        // Check if booking options exist
        List<TripBookingOption> bookingOptions = userSession.getBookingOptions();
        if (bookingOptions == null || bookingOptions.isEmpty()) {
            userSession.setCurrentStep("book_now");
            return languageService.getMessage(language, "session_expired") + "\n" +
                    languageService.getMessage(language, "book_now");
        }

        try {
            int selection = Integer.parseInt(input);

            if (selection < 1 || selection > bookingOptions.size()) {
                return languageService.getMessage(language, "invalid_selection") + "\n" +
                        formatTripsList(userSession.getAvailableTrips(), language,
                                userSession.getOrigin(), userSession.getDestination(), userSession);
            }

            TripBookingOption selectedOption = bookingOptions.get(selection - 1);

            // Log successful booking
            log.info("=== SUCCESSFUL BOOKING ===");
            log.info("Phone Number: {}", userSession.getPhoneNumber());
            log.info("Trip ID: {}", selectedOption.getTripId());
            log.info("Origin: {} (ID: {}, Waypoint: {})",
                    selectedOption.getOriginName(),
                    selectedOption.getOriginLocationId(),
                    selectedOption.isOriginIsWaypoint());
            log.info("Destination: {} (ID: {}, Waypoint: {})",
                    selectedOption.getDestinationName(),
                    selectedOption.getDestinationLocationId(),
                    selectedOption.isDestinationIsWaypoint());
            log.info("Price: {}", selectedOption.getPrice());
            log.info("Departure Time: {}", selectedOption.getDepartureTime());
            log.info("Available Seats: {}", selectedOption.getAvailableSeats());
            log.info("Payment will be processed later...");
            log.info("=========================");

            // Reset session for new booking
            userSession.setCurrentStep("book_now");
            userSession.setOrigin(null);
            userSession.setDestination(null);
            userSession.setAvailableTrips(null);
            userSession.setBookingOptions(null);

            return languageService.getMessage(language, "trip_selected",
                    selectedOption.getOriginName(),
                    selectedOption.getDestinationName(),
                    selectedOption.getPrice());

        } catch (NumberFormatException e) {
            return languageService.getMessage(language, "invalid_selection") + "\n" +
                    languageService.getMessage(language, "book_now");
        }
    }

    private String formatTripsList(List<TripDto> trips, String language, String userOrigin, String userDestination, UserSession userSession) {
        StringBuilder sb = new StringBuilder();
        sb.append(languageService.getMessage(language, "available_trips")).append("\n");

        List<TripBookingOption> bookingOptions = new ArrayList<>();

        for (int i = 0; i < trips.size(); i++) {
            TripDto trip = trips.get(i);

            // Find matched locations and their IDs
            MatchedLocation matchedOriginInfo = findMatchingLocationWithId(userOrigin, trip, true);
            MatchedLocation matchedDestinationInfo = findMatchingLocationWithId(userDestination, trip, false);

            // Create booking option with correct location IDs
            TripBookingOption bookingOption = TripBookingOption.builder()
                    .tripId(trip.getId())
                    .originLocationId(matchedOriginInfo.getLocationId())
                    .destinationLocationId(matchedDestinationInfo.getLocationId())
                    .originName(matchedOriginInfo.getName())
                    .destinationName(matchedDestinationInfo.getName())
                    .price(getPriceFromTrip(trip))
                    .departureTime(formatDepartureTime(trip.getDepartureTime()))
                    .availableSeats(trip.getSeats())
                    .originIsWaypoint(matchedOriginInfo.isWaypoint())
                    .destinationIsWaypoint(matchedDestinationInfo.isWaypoint())
                    .build();

            bookingOptions.add(bookingOption);

            sb.append(String.format("%d. %s -> %s (Price: %s, Time: %s)\n",
                    i + 1,
                    matchedOriginInfo.getName(),
                    matchedDestinationInfo.getName(),
                    getPriceFromTrip(trip),
                    formatDepartureTime(trip.getDepartureTime())));
        }

        // Save booking options to user session
        userSession.setBookingOptions(bookingOptions);

        sb.append(languageService.getMessage(language, "select_trip"));
        return sb.toString();
    }

    private MatchedLocation findMatchingLocationWithId(String userInput, TripDto trip, boolean isOrigin) {
        String userInputLower = userInput.toLowerCase().trim();

        // Check if input is a 5-digit numeric code
        boolean isNumericCode = userInput.matches("\\d{5}");

        // Check route locations first
        if (trip.getRoute() != null) {
            LocationDto location = isOrigin ? trip.getRoute().getOrigin() : trip.getRoute().getDestination();
            if (location != null) {
                // Check for numeric code match first
                if (isNumericCode && location.getCode() != null && location.getCode().equals(userInput)) {
                    String displayName = location.getCustomName() != null ?
                        location.getCustomName() : location.getGooglePlaceName();
                    return new MatchedLocation(location.getId(), displayName, false);
                }

                // Check custom name
                if (location.getCustomName() != null &&
                        containsPartialMatch(location.getCustomName(), userInputLower)) {
                    return new MatchedLocation(location.getId(), location.getCustomName(), false);
                }

                // Check Google place name
                if (location.getGooglePlaceName() != null &&
                        containsPartialMatch(location.getGooglePlaceName(), userInputLower)) {
                    return new MatchedLocation(location.getId(), location.getGooglePlaceName(), false);
                }
            }
        }

        // Check waypoints
        if (trip.getWaypoints() != null) {
            for (TripWaypointDto waypoint : trip.getWaypoints()) {
                LocationDto loc = waypoint.getLocation();
                if (loc != null) {
                    // Check for numeric code match first
                    if (isNumericCode && loc.getCode() != null && loc.getCode().equals(userInput)) {
                        String displayName = loc.getCustomName() != null ?
                            loc.getCustomName() : loc.getGooglePlaceName();
                        return new MatchedLocation(loc.getId(), displayName, true);
                    }

                    // Check custom name
                    if (loc.getCustomName() != null &&
                            containsPartialMatch(loc.getCustomName(), userInputLower)) {
                        return new MatchedLocation(loc.getId(), loc.getCustomName(), true);
                    }

                    // Check Google place name
                    if (loc.getGooglePlaceName() != null &&
                            containsPartialMatch(loc.getGooglePlaceName(), userInputLower)) {
                        return new MatchedLocation(loc.getId(), loc.getGooglePlaceName(), true);
                    }
                }
            }
        }

        // Fallback: return the best available location
        if (trip.getRoute() != null) {
            LocationDto location = isOrigin ? trip.getRoute().getOrigin() : trip.getRoute().getDestination();
            if (location != null) {
                String name = location.getCustomName() != null ?
                        location.getCustomName() : location.getGooglePlaceName();
                if (name != null) {
                    return new MatchedLocation(location.getId(), name, false);
                }
            }
        }

        return new MatchedLocation(null, userInput, false);
    }

    private String findMatchingLocationName(String userInput, TripDto trip, boolean isOrigin) {
        String userInputLower = userInput.toLowerCase().trim();

        // Check route locations first
        if (trip.getRoute() != null) {
            LocationDto location = isOrigin ? trip.getRoute().getOrigin() : trip.getRoute().getDestination();
            if (location != null) {
                if (location.getCustomName() != null &&
                    containsPartialMatch(location.getCustomName(), userInputLower)) {
                    return location.getCustomName();
                }
                if (location.getGooglePlaceName() != null &&
                    containsPartialMatch(location.getGooglePlaceName(), userInputLower)) {
                    return location.getGooglePlaceName();
                }
            }
        }

        // Check waypoints
        if (trip.getWaypoints() != null) {
            for (TripWaypointDto waypoint : trip.getWaypoints()) {
                LocationDto loc = waypoint.getLocation();
                if (loc != null) {
                    if (loc.getCustomName() != null &&
                        containsPartialMatch(loc.getCustomName(), userInputLower)) {
                        return loc.getCustomName();
                    }
                    if (loc.getGooglePlaceName() != null &&
                        containsPartialMatch(loc.getGooglePlaceName(), userInputLower)) {
                        return loc.getGooglePlaceName();
                    }
                }
            }
        }

        // Fallback: return the best available location name even if no match
        if (trip.getRoute() != null) {
            LocationDto location = isOrigin ? trip.getRoute().getOrigin() : trip.getRoute().getDestination();
            if (location != null) {
                if (location.getCustomName() != null) {
                    return location.getCustomName();
                }
                if (location.getGooglePlaceName() != null) {
                    return location.getGooglePlaceName();
                }
            }
        }

        return userInput; // Return user input if no location found
    }

    private boolean containsPartialMatch(String locationName, String userInput) {
        if (locationName == null || userInput == null) return false;

        String locationLower = locationName.toLowerCase().trim();

        // Check if either contains the other for partial matching
        return locationLower.contains(userInput) || userInput.contains(locationLower);
    }

    // Helper methods to safely extract trip information
    private String getOriginFromTrip(TripDto trip) {
        if (trip.getRoute() != null && trip.getRoute().getOriginId() != null) {
            return trip.getRoute().getOrigin().getCustomName() != null ?
                    trip.getRoute().getOrigin().getCustomName() :
                    trip.getRoute().getOrigin().getGooglePlaceName();
        }
        // Fallback: get from first waypoint
        if (trip.getWaypoints() != null && !trip.getWaypoints().isEmpty()) {
            var firstWaypoint = trip.getWaypoints().get(0);
            if (firstWaypoint.getLocation() != null) {
                return firstWaypoint.getLocation().getCustomName() != null ?
                        firstWaypoint.getLocation().getCustomName() :
                        firstWaypoint.getLocation().getGooglePlaceName();
            }
        }
        return "N/A";
    }

    private String getDestinationFromTrip(TripDto trip) {
        if (trip.getRoute() != null && trip.getRoute().getDestination() != null) {
            return trip.getRoute().getDestination().getCustomName() != null ?
                    trip.getRoute().getDestination().getCustomName() :
                    trip.getRoute().getDestination().getGooglePlaceName();
        }
        // Fallback: get from last waypoint
        if (trip.getWaypoints() != null && !trip.getWaypoints().isEmpty()) {
            var lastWaypoint = trip.getWaypoints().get(trip.getWaypoints().size() - 1);
            if (lastWaypoint.getLocation() != null) {
                return lastWaypoint.getLocation().getCustomName() != null ?
                        lastWaypoint.getLocation().getCustomName() :
                        lastWaypoint.getLocation().getGooglePlaceName();
            }
        }
        return "N/A";
    }

    private String getPriceFromTrip(TripDto trip) {
        // Try to get price from route
        if (trip.getRoute() != null && trip.getRoute().getRoutePrice() != null) {
            return trip.getRoute().getRoutePrice().toString();
        }

        // Try to get price from waypoints (usually the destination waypoint)
        if (trip.getWaypoints() != null && !trip.getWaypoints().isEmpty()) {
            for (var waypoint : trip.getWaypoints()) {
                if (waypoint.getPrice() != null) {
                    return waypoint.getPrice().toString();
                }
            }
        }

        return "N/A";
    }

    private String formatDepartureTime(Long departureTime) {
        if (departureTime == null) {
            return "N/A";
        }

        try {
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(departureTime),
                    ZoneId.systemDefault()
            );
            return dateTime.format(DateTimeFormatter.ofPattern("HH:mm"));
        } catch (Exception e) {
            log.warn("Error formatting departure time: {}", departureTime, e);
            return "N/A";
        }
    }
}