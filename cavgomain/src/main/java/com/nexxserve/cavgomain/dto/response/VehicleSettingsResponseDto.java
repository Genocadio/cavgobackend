package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.entity.VehicleSettings;
import lombok.Data;

@Data
public class VehicleSettingsResponseDto {
    private Long id;
    private Long vehicleId;
    private Boolean logout;
    private Boolean devmode;
    private Boolean deactivate;
    private Boolean appmode;
    private Boolean simulate;

    public static VehicleSettingsResponseDto fromEntity(VehicleSettings entity) {
        VehicleSettingsResponseDto dto = new VehicleSettingsResponseDto();
        dto.setId(entity.getId());
        dto.setVehicleId(entity.getVehicle().getId());
        dto.setLogout(entity.getLogout());
        dto.setDevmode(entity.getDevmode());
        dto.setDeactivate(entity.getDeactivate());
        dto.setAppmode(entity.getAppmode());
        dto.setSimulate(entity.getSimulate());
        return dto;
    }
}

