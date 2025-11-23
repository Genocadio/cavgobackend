package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.dto.response.InternalVehicleResponseDto;
import com.nexxserve.cavgomain.dto.response.InternalWorkerResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AggregatorSyncService {

    private final RestTemplate restTemplate;
    private final ScheduledExecutorService scheduledExecutorService;
    private final InternalApiService internalApiService;

    @Value("${aggregator.base-url:}")
    private String aggregatorBaseUrl;

    // Store scheduled tasks per company ID
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /**
     * Schedule sync for a company after 10 minutes.
     * If a task already exists for this company, cancel it and create a new one.
     */
    public void scheduleCompanySync(Long companyId) {
        if (aggregatorBaseUrl == null || aggregatorBaseUrl.isEmpty()) {
            log.debug("Aggregator base URL not configured, skipping sync scheduling");
            return;
        }

        // Cancel existing task if any
        ScheduledFuture<?> existingTask = scheduledTasks.get(companyId);
        if (existingTask != null && !existingTask.isDone()) {
            existingTask.cancel(false);
            log.debug("Cancelled existing sync task for company {}", companyId);
        }

        // Schedule new task for 10 minutes
        ScheduledFuture<?> newTask = scheduledExecutorService.schedule(
                () -> syncCompanyData(companyId),
                10,
                TimeUnit.MINUTES
        );

        scheduledTasks.put(companyId, newTask);
        log.debug("Scheduled sync for company {} in 10 minutes", companyId);
    }

    /**
     * Immediately sync company data (for creation events)
     */
    @Async
    public void syncCompanyDataImmediately(Long companyId) {
        if (aggregatorBaseUrl == null || aggregatorBaseUrl.isEmpty()) {
            log.debug("Aggregator base URL not configured, skipping immediate sync");
            return;
        }

        syncCompanyData(companyId);
    }

    /**
     * Sync all vehicles and workers for a company to the aggregator
     */
    private void syncCompanyData(Long companyId) {
        try {
            log.info("Syncing data for company {} to aggregator", companyId);

            // Get all vehicles for the company
            List<InternalVehicleResponseDto> vehicles = internalApiService.getVehiclesByCompany(companyId);
            syncVehicles(companyId, vehicles);

            // Get all workers for the company
            List<InternalWorkerResponseDto> workers = internalApiService.getWorkersByCompany(companyId);
            syncWorkers(companyId, workers);

            // Remove completed task from map
            scheduledTasks.remove(companyId);
            log.info("Successfully synced data for company {} to aggregator", companyId);

        } catch (Exception e) {
            log.error("Error syncing data for company {} to aggregator", companyId, e);
            // Don't throw - we don't want to block the main flow
        }
    }

    /**
     * Sync vehicles to aggregator
     */
    private void syncVehicles(Long companyId, List<InternalVehicleResponseDto> vehicles) {
        String url = aggregatorBaseUrl + "/company/" + companyId + "/vehicle";
        
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<List<InternalVehicleResponseDto>> request = new HttpEntity<>(vehicles, headers);

            restTemplate.exchange(url, HttpMethod.POST, request, Void.class);
            log.debug("Successfully synced {} vehicles for company {}", vehicles.size(), companyId);

        } catch (Exception e) {
            log.error("Error syncing vehicles for company {} to aggregator at {}", companyId, url, e);
        }
    }

    /**
     * Sync workers to aggregator
     */
    private void syncWorkers(Long companyId, List<InternalWorkerResponseDto> workers) {
        String url = aggregatorBaseUrl + "/company/" + companyId + "/worker";
        
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<List<InternalWorkerResponseDto>> request = new HttpEntity<>(workers, headers);

            restTemplate.exchange(url, HttpMethod.POST, request, Void.class);
            log.debug("Successfully synced {} workers for company {}", workers.size(), companyId);

        } catch (Exception e) {
            log.error("Error syncing workers for company {} to aggregator at {}", companyId, url, e);
        }
    }
}


