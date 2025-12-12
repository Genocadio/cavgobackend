package com.nexxserve.cavgomain.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VehicleSettingsUpdateDto {
    private Boolean logout;
    private Boolean devmode;
    private Boolean deactivate;
    private Boolean appmode;
    private Boolean simulate;
}

