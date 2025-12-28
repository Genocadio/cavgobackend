package com.gocavgo.Navigation.config;

import com.gocavgo.Navigation.routing.OsrmConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
@RequiredArgsConstructor
@Slf4j
public class OsrmHealthCheck {
    private final OsrmConfig osrmConfig;
    
    @PostConstruct
    public void checkOsrmConnectivity() {
        log.info("Checking OSRM API connectivity at: {}", osrmConfig.getUrl());
        
        try {
            RestClient restClient = RestClient.builder()
                    .baseUrl(osrmConfig.getUrl())
                    .build();
            
            // Use HEAD request or just check if we can connect
            // OSRM nearest endpoint with a simple coordinate
            org.springframework.http.ResponseEntity<Void> response = restClient.get()
                    .uri("/nearest/v1/driving/13.4050,52.5200")
                    .retrieve()
                    .toBodilessEntity();
            
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("✓ OSRM API is reachable and responding at: {} (Status: {})", 
                        osrmConfig.getUrl(), response.getStatusCode());
            } else {
                log.warn("⚠ OSRM API returned non-success status at: {}. Status: {}", 
                        osrmConfig.getUrl(), response.getStatusCode());
            }
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.error("✗ OSRM API is NOT reachable at: {}. Error: {}", osrmConfig.getUrl(), e.getMessage());
            log.error("  Please ensure OSRM server is running and accessible. Navigation features will not work.");
        } catch (org.springframework.web.client.HttpClientErrorException | org.springframework.web.client.HttpServerErrorException e) {
            // Even HTTP errors mean OSRM is reachable, just not working correctly
            log.warn("⚠ OSRM API returned HTTP error at: {}. Status: {}. Error: {}", 
                    osrmConfig.getUrl(), e.getStatusCode(), e.getMessage());
            log.info("  OSRM server is reachable but may have configuration issues.");
        } catch (org.springframework.web.client.RestClientException e) {
            // Handle parsing/compression issues - OSRM is reachable but response format issue
            if (e.getMessage() != null && e.getMessage().contains("extracting response")) {
                log.info("✓ OSRM API is reachable at: {} (response parsing issue, but server is accessible)", 
                        osrmConfig.getUrl());
            } else {
                log.error("✗ Failed to check OSRM API connectivity at: {}. Error: {}", 
                        osrmConfig.getUrl(), e.getMessage());
            }
        } catch (Exception e) {
            log.error("✗ Failed to check OSRM API connectivity at: {}. Error: {}", 
                    osrmConfig.getUrl(), e.getMessage());
        }
    }
}

