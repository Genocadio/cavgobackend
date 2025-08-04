package com.gocavgo.ussdservice.service;

import com.gocavgo.ussdservice.dto.LocationDto;
import com.gocavgo.ussdservice.dto.TripWaypointDto;
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

        // Handle different navigation scenarios
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
                    userSession.getOrigin(), userSession.getDestination());

        } catch (Exception e) {
            log.error("Error searching for trips", e);
            userSession.setCurrentStep("book_now");
            return languageService.getMessage(language, "search_error") + "\n" +
                    languageService.getMessage(language, "book_now");
        }
    }

    private String handleTripSelection(UserSession userSession, String input) {
        String language = userSession.getLanguage();

        try {
            int selection = Integer.parseInt(input);
            List<TripDto> availableTrips = userSession.getAvailableTrips();

            if (selection < 1 || selection > availableTrips.size()) {
                return languageService.getMessage(language, "invalid_selection") + "\n" +
                        formatTripsList(availableTrips, language,
                                userSession.getOrigin(), userSession.getDestination());
            }

            TripDto selectedTrip = availableTrips.get(selection - 1);

            // Log the selected trip with correct field access
            log.info("User {} selected trip: ID={}, Route={}, Price={}, DepartureTime={}",
                    userSession.getPhoneNumber(),
                    selectedTrip.getId(),
                    selectedTrip.getRoute() != null ?
                            String.format("%s -> %s", getOriginFromTrip(selectedTrip), getDestinationFromTrip(selectedTrip)) : "N/A",
                    getPriceFromTrip(selectedTrip),
                    formatDepartureTime(selectedTrip.getDepartureTime()));

            // Reset session for new booking
            userSession.setCurrentStep("book_now");
            userSession.setOrigin(null);
            userSession.setDestination(null);
            userSession.setAvailableTrips(null);

            return languageService.getMessage(language, "trip_selected",
                    getOriginFromTrip(selectedTrip),
                    getDestinationFromTrip(selectedTrip),
                    getPriceFromTrip(selectedTrip));

        } catch (NumberFormatException e) {
            List<TripDto> availableTrips = userSession.getAvailableTrips();
            return languageService.getMessage(language, "invalid_selection") + "\n" +
                    formatTripsList(availableTrips, language,
                            userSession.getOrigin(), userSession.getDestination());
        }
    }

    private String formatTripsList(List<TripDto> trips, String language, String userOrigin, String userDestination) {
        StringBuilder sb = new StringBuilder();
        sb.append(languageService.getMessage(language, "available_trips")).append("\n");

        for (int i = 0; i < trips.size(); i++) {
            TripDto trip = trips.get(i);
            String matchedOrigin = findMatchingLocationName(userOrigin, trip, true);
            String matchedDestination = findMatchingLocationName(userDestination, trip, false);

            sb.append(String.format("%d. %s -> %s (Price: %s, Time: %s)\n",
                    i + 1,
                    matchedOrigin,
                    matchedDestination,
                    getPriceFromTrip(trip),
                    formatDepartureTime(trip.getDepartureTime())));
        }

        sb.append(languageService.getMessage(language, "select_trip"));
        return sb.toString();
    }

    private String findMatchingLocationName(String userInput, TripDto trip, boolean isOrigin) {
        if (trip.getRoute() != null) {
            LocationDto location = isOrigin ? trip.getRoute().getOrigin() : trip.getRoute().getDestination();
            if (location != null) {
                if (location.getCustomName() != null && location.getCustomName().equalsIgnoreCase(userInput)) {
                    return location.getCustomName();
                }
                if (location.getGooglePlaceName() != null && location.getGooglePlaceName().equalsIgnoreCase(userInput)) {
                    return location.getGooglePlaceName();
                }
            }
        }
        if (trip.getWaypoints() != null) {
            for (TripWaypointDto waypoint : trip.getWaypoints()) {
                LocationDto loc = waypoint.getLocation();
                if (loc != null) {
                    if (loc.getCustomName() != null && loc.getCustomName().equalsIgnoreCase(userInput)) {
                        return loc.getCustomName();
                    }
                    if (loc.getGooglePlaceName() != null && loc.getGooglePlaceName().equalsIgnoreCase(userInput)) {
                        return loc.getGooglePlaceName();
                    }
                }
            }
        }
        return "N/A";
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