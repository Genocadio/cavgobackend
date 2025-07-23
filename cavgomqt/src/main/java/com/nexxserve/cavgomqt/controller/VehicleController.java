package com.nexxserve.cavgomqt.controller;

import com.nexxserve.cavgomqt.dto.VehicleDto;
import com.nexxserve.cavgomqt.entity.MqttVehicleEntity;
import com.nexxserve.cavgomqt.service.VehicleRegistryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/mqtt/vehicles")
public class VehicleController {

    @Autowired
    private VehicleRegistryService vehicleRegistryService;

    /**
     * Get all vehicles from the backend
     */
    @GetMapping("/backend")
    public ResponseEntity<List<VehicleDto>> getAllBackendVehicles() {
        List<VehicleDto> vehicles = vehicleRegistryService.fetchAllVehicles();
        return ResponseEntity.ok(vehicles);
    }

    /**
     * Get all MQTT-enabled vehicles
     */
    @GetMapping("/mqtt")
    public ResponseEntity<List<MqttVehicleEntity>> getAllMqttVehicles() {
        List<MqttVehicleEntity> vehicles = vehicleRegistryService.getAllMqttVehicles();
        return ResponseEntity.ok(vehicles);
    }

    /**
     * Get available MQTT vehicles
     */
    @GetMapping("/mqtt/available")
    public ResponseEntity<List<MqttVehicleEntity>> getAvailableMqttVehicles() {
        List<MqttVehicleEntity> vehicles = vehicleRegistryService.getAvailableMqttVehicles();
        return ResponseEntity.ok(vehicles);
    }

    /**
     * Add a vehicle to MQTT registry
     */
    @PostMapping("/mqtt/add/{vehicleId}")
    public ResponseEntity<Map<String, Object>> addVehicleToMqttRegistry(@PathVariable Long vehicleId) {
        boolean success = vehicleRegistryService.addVehicleToMqttRegistry(vehicleId);

        Map<String, Object> response = Map.of(
                "success", success,
                "message", success ? "Vehicle added to MQTT registry" : "Failed to add vehicle",
                "vehicleId", vehicleId
        );

        return success ? ResponseEntity.ok(response) : ResponseEntity.badRequest().body(response);
    }

    /**
     * Remove a vehicle from MQTT registry
     */
    @DeleteMapping("/mqtt/remove/{vehicleId}")
    public ResponseEntity<Map<String, Object>> removeVehicleFromMqttRegistry(@PathVariable Long vehicleId) {
        boolean success = vehicleRegistryService.removeVehicleFromMqttRegistry(vehicleId);

        Map<String, Object> response = Map.of(
                "success", success,
                "message", success ? "Vehicle removed from MQTT registry" : "Vehicle not found in registry",
                "vehicleId", vehicleId
        );

        return success ? ResponseEntity.ok(response) : ResponseEntity.badRequest().body(response);
    }

    /**
     * Get registry statistics
     */
    @GetMapping("/mqtt/stats")
    public ResponseEntity<Map<String, Object>> getRegistryStats() {
        String stats = vehicleRegistryService.getRegistryStats();
        int totalMqtt = vehicleRegistryService.getAllMqttVehicles().size();
        int availableMqtt = vehicleRegistryService.getAvailableMqttVehicles().size();

        Map<String, Object> response = Map.of(
                "stats", stats,
                "totalMqttVehicles", totalMqtt,
                "availableMqttVehicles", availableMqtt
        );

        return ResponseEntity.ok(response);
    }
}
