package com.gocavgo.ussdservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gocavgo.ussdservice.dto.PaginatedTripsResponse;
import com.gocavgo.ussdservice.dto.TripDto;
import com.gocavgo.ussdservice.dto.TripFilters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TripService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.backend.base-url:https://api.gocavgo.com/api}")
    private String backendBaseUrl;

    /**
     * Get paginated trips with optional filtering
     */
    public PaginatedTripsResponse getTrips(TripFilters filters) {
        try {
            log.info("Fetching trips with filters: {}", filters);

            String url = buildTripsUrl(filters);

            HttpHeaders headers = createHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<PaginatedTripsResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    PaginatedTripsResponse.class
            );

            PaginatedTripsResponse result = response.getBody();
            log.info("Successfully fetched {} trips", result != null ? result.getTrips().size() : 0);

            return result;

        } catch (Exception e) {
            log.error("Error fetching trips with filters: {}", filters, e);
            throw new RuntimeException("Failed to fetch trips from backend", e);
        }
    }

    /**
     * Get a specific trip by ID
     */
    public Optional<TripDto> getTripById(Long tripId) {
        try {
            log.info("Fetching trip with ID: {}", tripId);

            String url = backendBaseUrl + "/navig/trips/" + tripId;

            HttpHeaders headers = createHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<TripDto> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    TripDto.class
            );

            TripDto trip = response.getBody();
            log.info("Successfully fetched trip: {}", tripId);

            return Optional.ofNullable(trip);

        } catch (Exception e) {
            log.error("Error fetching trip with ID: {}", tripId, e);
            return Optional.empty();
        }
    }

    /**
     * Get trips by origin location
     */
    public List<TripDto> getTripsByOrigin(String origin, Integer limit, Integer offset) {
        TripFilters filters = TripFilters.builder()
                .origin(origin)
                .limit(limit != null ? limit : 20)
                .offset(offset != null ? offset : 0)
                .build();

        PaginatedTripsResponse response = getTrips(filters);
        return response.getTrips();
    }

    /**
     * Get trips by destination location
     */
    public List<TripDto> getTripsByDestination(String destination, Integer limit, Integer offset) {
        TripFilters filters = TripFilters.builder()
                .destination(destination)
                .limit(limit != null ? limit : 20)
                .offset(offset != null ? offset : 0)
                .build();

        PaginatedTripsResponse response = getTrips(filters);
        return response.getTrips();
    }

    /**
     * Get trips by origin and destination
     */
    public List<TripDto> getTripsByRoute(String origin, String destination, Integer limit, Integer offset) {
        TripFilters filters = TripFilters.builder()
                .origin(origin)
                .destination(destination)
                .limit(limit != null ? limit : 20)
                .offset(offset != null ? offset : 0)
                .build();

        PaginatedTripsResponse response = getTrips(filters);
        return response.getTrips();
    }

    /**
     * Get all trips with pagination
     */
    public PaginatedTripsResponse getAllTrips(Integer limit, Integer offset) {
        TripFilters filters = TripFilters.builder()
                .limit(limit != null ? limit : 20)
                .offset(offset != null ? offset : 0)
                .build();

        return getTrips(filters);
    }

    /**
     * Build the URL for trips API with query parameters
     */
    private String buildTripsUrl(TripFilters filters) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(backendBaseUrl + "/navig/trips");

        if (filters.getOrigin() != null && !filters.getOrigin().trim().isEmpty()) {
            builder.queryParam("origin", filters.getOrigin());
        }

        if (filters.getDestination() != null && !filters.getDestination().trim().isEmpty()) {
            builder.queryParam("destination", filters.getDestination());
        }

        builder.queryParam("limit", filters.getLimit());
        builder.queryParam("offset", filters.getOffset());

        return builder.toUriString();
    }

    /**
     * Create HTTP headers for API requests
     */
    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");

        // Add authentication headers if needed
        // String token = getAuthToken();
        // if (token != null) {
        //     headers.set("Authorization", "Bearer " + token);
        // }

        return headers;
    }

    /**
     * Check if trip service is available
     */
    public boolean isServiceAvailable() {
        try {
            String healthUrl = backendBaseUrl + "/health";
            ResponseEntity<String> response = restTemplate.getForEntity(healthUrl, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("Trip service health check failed", e);
            return false;
        }
    }
}
