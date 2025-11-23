package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.entity.Vehicle;
import com.nexxserve.cavgomain.enums.VehicleStatus;
import lombok.Data;

import java.time.ZoneOffset;

@Data
public class InternalVehicleResponseDto {
    private String id;
    private String companyId;
    private String companyCode;
    private String plate;
    private String model;
    private String make;
    private Integer capacity;
    private String connectionStatus; // ONLINE or OFFLINE
    private String operationalStatus; // VehicleStatus enum value
    private InternalVehicleLocationDto currentLocation;
    private String lastUpdated;

    @Data
    public static class InternalVehicleLocationDto {
        private Double latitude;
        private Double longitude;
        private String address; // nullable
        private String timestamp; // ISO 8601 format
        private Double bearing;
        private Double speed;
    }

    public static InternalVehicleResponseDto fromEntity(Vehicle vehicle, InternalVehicleLocationDto location) {
        InternalVehicleResponseDto dto = new InternalVehicleResponseDto();
        dto.setId(vehicle.getId().toString());
        dto.setCompanyId(vehicle.getCompany().getId().toString());
        dto.setCompanyCode(vehicle.getCompany().getCompanyCode());
        dto.setPlate(vehicle.getLicensePlate());
        dto.setModel(vehicle.getModel());
        dto.setMake(vehicle.getMake());
        dto.setCapacity(vehicle.getCapacity());
        
        // Map connection status based on isOnline()
        dto.setConnectionStatus(vehicle.isOnline() ? "ONLINE" : "OFFLINE");
        
        // Map operational status from VehicleStatus enum
        dto.setOperationalStatus(mapVehicleStatus(vehicle.getStatus()));
        
        dto.setCurrentLocation(location);
        
        // Set lastUpdated from vehicle's updatedAt or createdAt
        if (vehicle.getUpdatedAt() != null) {
            dto.setLastUpdated(vehicle.getUpdatedAt().toInstant(ZoneOffset.UTC).toString());
        } else if (vehicle.getCreatedAt() != null) {
            dto.setLastUpdated(vehicle.getCreatedAt().toInstant(ZoneOffset.UTC).toString());
        }
        
        return dto;
    }

    private static String mapVehicleStatus(VehicleStatus status) {
        if (status == null) {
            return "AVAILABLE";
        }
        return status.name();
    }
}

