package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.entity.VehicleLocation;
import lombok.Data;

@Data
public class VehicleLocationResponseDto {
    private Long id;
    private Long vehicleId;
    private Double latitude;
    private Double longitude;
    private Double speed;
    private Double accuracy;
    private Double bearing;
    private Long timestamp;
    private String recordedAt;

    public static VehicleLocationResponseDto fromEntity(VehicleLocation entity) {
        VehicleLocationResponseDto dto = new VehicleLocationResponseDto();
        dto.setId(entity.getId());
        dto.setVehicleId(entity.getVehicle().getId());
        dto.setLatitude(entity.getLatitude());
        dto.setLongitude(entity.getLongitude());
        dto.setSpeed(entity.getSpeed());
        dto.setAccuracy(entity.getAccuracy());
        dto.setBearing(entity.getBearing());
        dto.setTimestamp(entity.getTimestamp());
        dto.setRecordedAt(entity.getRecordedAt().toString());
        return dto;
    }
}



