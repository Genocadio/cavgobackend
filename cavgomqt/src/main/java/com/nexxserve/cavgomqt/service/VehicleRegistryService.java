package com.nexxserve.cavgomqt.service;

import com.nexxserve.cavgomqt.dto.VehicleDto;
import com.nexxserve.cavgomqt.entity.MqttVehicleEntity;
import com.nexxserve.cavgomqt.repository.MqttVehicleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

@Service
public class VehicleRegistryService {

    @Value("${vehicle.backend.url:http://localhost:8060}")
    private String vehicleBackendUrl;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private MqttVehicleRepository mqttVehicleRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void fetchVehiclesOnStartup() {
        System.out.println("🚗 Fetching vehicles from backend: " + vehicleBackendUrl);
        try {
            List<VehicleDto> vehicles = fetchAllVehicles();
            for (VehicleDto vehicle : vehicles) {
                if (!mqttVehicleRepository.existsByVehicleId(vehicle.getId())) {
                    MqttVehicleEntity entity = new MqttVehicleEntity();
                    entity.setVehicleId(vehicle.getId());
                    entity.setLicensePlate(vehicle.getLicensePlate());
                    entity.setIsOnline(false);
                    entity.setLastHeartbeat(0L);
                    entity.setCreatedAt(java.time.LocalDateTime.now());
                    entity.setUpdatedAt(java.time.LocalDateTime.now());
                    mqttVehicleRepository.save(entity);
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Failed to fetch vehicles on startup: " + e.getMessage());
        }
    }

    public List<VehicleDto> fetchAllVehicles() {
        String url = vehicleBackendUrl + "/main/vehicles";
        try {
            ResponseEntity<List<VehicleDto>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<VehicleDto>>() {}
            );
            List<VehicleDto> vehicles = response.getBody();
            System.out.println("✅ Fetched " + (vehicles != null ? vehicles.size() : 0) + " vehicles from backend");
            return vehicles != null ? vehicles : List.of();
        } catch (Exception e) {
            System.err.println("❌ Error fetching vehicles: " + e.getMessage());
            return List.of();
        }
    }

    public boolean addVehicleToMqttRegistry(Long vehicleId) {
        List<VehicleDto> allVehicles = fetchAllVehicles();
        VehicleDto vehicle = allVehicles.stream()
                .filter(v -> v.getId().equals(vehicleId))
                .findFirst()
                .orElse(null);

        if (vehicle == null) {
            System.err.println("❌ Vehicle with ID " + vehicleId + " not found in backend");
            return false;
        }

        if (mqttVehicleRepository.existsByVehicleId(vehicleId)) {
            System.err.println("❌ Vehicle already exists in registry");
            return false;
        }

        MqttVehicleEntity entity = new MqttVehicleEntity();
        entity.setVehicleId(vehicle.getId());
        entity.setLicensePlate(vehicle.getLicensePlate());
        entity.setIsOnline(false);
        entity.setLastHeartbeat(0L);
        entity.setCreatedAt(java.time.LocalDateTime.now());
        entity.setUpdatedAt(java.time.LocalDateTime.now());
        mqttVehicleRepository.save(entity);

        System.out.println("✅ Added vehicle to MQTT registry: " + entity);
        return true;
    }

    public boolean removeVehicleFromMqttRegistry(Long vehicleId) {
        Optional<MqttVehicleEntity> entityOpt = mqttVehicleRepository.findByVehicleId(vehicleId);
        if (entityOpt.isPresent()) {
            mqttVehicleRepository.delete(entityOpt.get());
            System.out.println("✅ Removed vehicle from MQTT registry: " + entityOpt.get());
            return true;
        }
        return false;
    }

    public List<MqttVehicleEntity> getAllMqttVehicles() {
        return mqttVehicleRepository.findAll();
    }

    public List<MqttVehicleEntity> getAvailableMqttVehicles() {
        return mqttVehicleRepository.findAvailableVehicles();
    }


    public MqttVehicleEntity getVehicleByBackendId(Long vehicleId) {
        return mqttVehicleRepository.findByVehicleId(vehicleId).orElse(null);
    }

    public void updateVehicleStatus(Long vehicleId, boolean isOnline, long heartbeat) {
        Optional<MqttVehicleEntity> entityOpt = mqttVehicleRepository.findByVehicleId(vehicleId);
        if (entityOpt.isPresent()) {
            MqttVehicleEntity entity = entityOpt.get();
            entity.setIsOnline(isOnline);
            entity.setLastHeartbeat(heartbeat);
            entity.setUpdatedAt(java.time.LocalDateTime.now());
            mqttVehicleRepository.save(entity);
        }
    }

    public void setActiveTrip(Long vehicleId, String tripId) {
        Optional<MqttVehicleEntity> entityOpt = mqttVehicleRepository.findByVehicleId(vehicleId);
        if (entityOpt.isPresent()) {
            MqttVehicleEntity entity = entityOpt.get();
            entity.setCurrentTripId(tripId);
            entity.setUpdatedAt(java.time.LocalDateTime.now());
            mqttVehicleRepository.save(entity);
            System.out.println("✅ Vehicle " + vehicleId + " assigned to trip " + tripId);
        } else {
            System.err.println("❌ Vehicle " + vehicleId + " not found in registry");
        }
    }

    public void clearActiveTrip(Long vehicleId) {
        Optional<MqttVehicleEntity> entityOpt = mqttVehicleRepository.findByVehicleId(vehicleId);
        if (entityOpt.isPresent()) {
            MqttVehicleEntity entity = entityOpt.get();
            entity.setCurrentTripId(null);
            entity.setUpdatedAt(java.time.LocalDateTime.now());
            mqttVehicleRepository.save(entity);
            System.out.println("✅ Cleared active trip for vehicle " + vehicleId);
        } else {
            System.err.println("❌ Vehicle " + vehicleId + " not found in registry");
        }
    }


    public String getRegistryStats() {
        long total = mqttVehicleRepository.count();
        long online = mqttVehicleRepository.countByOnlineStatus(true);
        long onTrip = mqttVehicleRepository.countVehiclesOnTrip();
        long available = mqttVehicleRepository.findAvailableVehicles().size();

        return String.format("MQTT Registry: %d total, %d online, %d on trip, %d available",
                total, online, onTrip, available);
    }
}