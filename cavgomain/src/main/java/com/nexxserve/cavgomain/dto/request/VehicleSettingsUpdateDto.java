package com.nexxserve.cavgomain.dto.request;

import lombok.Data;

@Data
public class VehicleSettingsUpdateDto {
    private Boolean logout;
    private Boolean devmode;
    private Boolean deactivate;
    private Boolean appmode;
    private Boolean simulate;
}

