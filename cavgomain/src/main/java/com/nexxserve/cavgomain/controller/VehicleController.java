package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.request.VehicleRequestDto;
import com.nexxserve.cavgomain.dto.response.VehicleAssignmentResponseDto;
import com.nexxserve.cavgomain.dto.response.VehicleResponseDto;
import com.nexxserve.cavgomain.entity.Vehicle;
import com.nexxserve.cavgomain.entity.VehicleAssignment;
import com.nexxserve.cavgomain.service.VehicleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/main/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService vehicleService;

    @PostMapping
    public VehicleResponseDto createVehicle(@Valid @RequestBody VehicleRequestDto vehicle) {
        return vehicleService.createVehicle(vehicle);
    }

    @GetMapping("/{id}")
    public VehicleResponseDto getVehicle(@PathVariable Long id) {
        return vehicleService.getVehicleResponse(id);
    }

    @GetMapping
    public List<VehicleResponseDto> getAllVehicles() {
        return vehicleService.getAllVehicles();
    }

    @PutMapping("/{id}")
    public VehicleResponseDto updateVehicle(@PathVariable Long id, @RequestBody VehicleRequestDto vehicle) {
        return vehicleService.updateVehicle(id, vehicle);
    }

    @GetMapping("/company/{companyId}")
    public List<VehicleResponseDto> getcompanyVehicles(@PathVariable Long companyId) {
        return vehicleService.getCompanyVehicles(companyId);
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
}