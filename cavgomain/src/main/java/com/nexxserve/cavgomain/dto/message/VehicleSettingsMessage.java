package com.nexxserve.cavgomain.dto.message;

import com.nexxserve.cavgomain.entity.VehicleSettings;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VehicleSettingsMessage {
    private String licensePlate;
    private Boolean logout;
    private Boolean devmode;
    private Boolean deactivate;
    private Boolean appmode;
    private Boolean simulate;

    public static VehicleSettingsMessage fromEntity(VehicleSettings settings, String licensePlate) {
        VehicleSettingsMessage message = new VehicleSettingsMessage();
        message.setLicensePlate(licensePlate);
        message.setLogout(settings.getLogout());
        message.setDevmode(settings.getDevmode());
        message.setDeactivate(settings.getDeactivate());
        message.setAppmode(settings.getAppmode());
        message.setSimulate(settings.getSimulate());
        return message;
    }
}

