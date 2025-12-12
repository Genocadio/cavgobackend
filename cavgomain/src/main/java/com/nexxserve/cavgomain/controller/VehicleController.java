package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.request.VehicleRequestDto;
import com.nexxserve.cavgomain.dto.request.VehicleLoginRequestDto;
import com.nexxserve.cavgomain.dto.request.VehicleAssignmentRequestDto;
import com.nexxserve.cavgomain.dto.request.VehicleSettingsUpdateDto;
import com.nexxserve.cavgomain.dto.response.VehicleAssignmentResponseDto;
import com.nexxserve.cavgomain.dto.response.VehicleLocationResponseDto;
import com.nexxserve.cavgomain.dto.response.VehicleResponseDto;
import com.nexxserve.cavgomain.dto.response.VehicleSettingsResponseDto;
import com.nexxserve.cavgomain.service.VehicleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/main/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService vehicleService;

    @PostMapping
    public ResponseEntity<VehicleResponseDto> createVehicle(@Valid @RequestBody VehicleRequestDto vehicle) {
        var result = vehicleService.createVehicleWithPassword(vehicle);
        VehicleResponseDto body = result.response();
        body.setInitialPassword(result.initialPassword());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/driver/{id}")
    public VehicleResponseDto getVehicleByDriver(@PathVariable Long id) {
        return vehicleService.getByDriver(id);
    }

    @GetMapping("/{id}")
    public VehicleResponseDto getVehicle(@PathVariable Long id) {
        return vehicleService.getVehicleResponse(id);
    }

    @GetMapping
    public List<VehicleResponseDto> getAllVehicles(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timeLimit) {
        return vehicleService.getAllVehicles(timeLimit);
    }

    @PutMapping("/{id}")
    public VehicleResponseDto updateVehicle(@PathVariable Long id, @RequestBody VehicleRequestDto vehicle) {
        return vehicleService.updateVehicle(id, vehicle);
    }

    @GetMapping("/company/{companyId}")
    public List<VehicleResponseDto> getcompanyVehicles(
            @PathVariable Long companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timeLimit) {
        return vehicleService.getCompanyVehicles(companyId, timeLimit);
    }

    @DeleteMapping("/{id}")
    public void deleteVehicle(@PathVariable Long id) {
        vehicleService.deleteVehicle(id);
    }

    @PostMapping("/{vehicleId}/assign/{driverId}")
    public VehicleAssignmentResponseDto assignVehicleToDriver(
            @PathVariable Long vehicleId,
            @PathVariable Long driverId,
            @RequestParam(required = false) String notes) {
        return vehicleService.assignVehicleToDriver(vehicleId, driverId, notes);
    }

    @PostMapping("/assign")
    public VehicleAssignmentResponseDto assignVehicleToDriverWithDto(@Valid @RequestBody VehicleAssignmentRequestDto assignmentDto) {
        return vehicleService.assignVehicleToDriverWithDto(assignmentDto);
    }

    @PostMapping("/login")
    public VehicleResponseDto loginVehicle(@Valid @RequestBody VehicleLoginRequestDto request) {
        return vehicleService.loginVehicle(request.getCompanyCode(), request.getLicensePlate(), request.getPassword(), request.getPubKey());
    }

    @PostMapping("/{licensePlate}/password/reset")
    public ResponseEntity<String> resetVehiclePassword(@PathVariable String licensePlate) {
        String newPassword = vehicleService.regenerateVehiclePassword(licensePlate);
        return ResponseEntity.ok(newPassword);
    }

    @DeleteMapping("/{vehicleId}/unassign")
    public VehicleAssignmentResponseDto unassignVehicle(@PathVariable Long vehicleId) {
        return vehicleService.unassignVehicle(vehicleId);
    }

    @PutMapping("/{vehicleId}/swap/{newDriverId}")
    public VehicleAssignmentResponseDto swapAssignment(@PathVariable Long vehicleId, @PathVariable Long newDriverId) {
        return vehicleService.swapAssignment(vehicleId, newDriverId);
    }

    @PutMapping("/driver/{currentDriverId}/swap/{newDriverId}")
    public VehicleAssignmentResponseDto swapDriverAssignment(@PathVariable Long currentDriverId, @PathVariable Long newDriverId) {
        return vehicleService.swapDriverAssignment(currentDriverId, newDriverId);
    }

    // Vehicle Settings Endpoints
    @GetMapping("/{id}/settings")
    public VehicleSettingsResponseDto getVehicleSettings(@PathVariable Long id) {
        return vehicleService.getVehicleSettings(id);
    }

    @PutMapping("/{id}/settings")
    public VehicleSettingsResponseDto updateVehicleSettings(
            @PathVariable Long id,
            @Valid @RequestBody VehicleSettingsUpdateDto settingsDto) {
        return vehicleService.updateVehicleSettings(id, settingsDto);
    }

    // Vehicle Location Endpoints
    @GetMapping("/{id}/locations")
    public List<VehicleLocationResponseDto> getVehicleLocations(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since) {
        return vehicleService.getVehicleLocations(id, since);
    }

    @GetMapping("/{id}/location/latest")
    public VehicleLocationResponseDto getLatestVehicleLocation(@PathVariable Long id) {
        return vehicleService.getLatestVehicleLocation(id);
    }
}