package com.nexxserve.cavgomqt.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for vehicle settings messages
 * Received from RabbitMQ and published to MQTT
 * 
 * RabbitMQ:
 * - Exchange: vehicle.settings.exchange
 * - Routing Key: vehicle.settings.{vehicleId}
 * 
 * MQTT:
 * - Topic: car/{vehicleId}/settings
 */
public class VehicleSettingsMessage {
    
    @JsonProperty("licensePlate")
    private String licensePlate;
    
    @JsonProperty("logout")
    private Boolean logout;
    
    @JsonProperty("devmode")
    private Boolean devmode;
    
    @JsonProperty("deactivate")
    private Boolean deactivate;
    
    @JsonProperty("appmode")
    private Boolean appmode;
    
    @JsonProperty("simulate")
    private Boolean simulate;

    // Getters and setters
    public String getLicensePlate() {
        return licensePlate;
    }

    public void setLicensePlate(String licensePlate) {
        this.licensePlate = licensePlate;
    }

    public Boolean getLogout() {
        return logout;
    }

    public void setLogout(Boolean logout) {
        this.logout = logout;
    }

    public Boolean getDevmode() {
        return devmode;
    }

    public void setDevmode(Boolean devmode) {
        this.devmode = devmode;
    }

    public Boolean getDeactivate() {
        return deactivate;
    }

    public void setDeactivate(Boolean deactivate) {
        this.deactivate = deactivate;
    }

    public Boolean getAppmode() {
        return appmode;
    }

    public void setAppmode(Boolean appmode) {
        this.appmode = appmode;
    }

    public Boolean getSimulate() {
        return simulate;
    }

    public void setSimulate(Boolean simulate) {
        this.simulate = simulate;
    }

    @Override
    public String toString() {
        return "VehicleSettingsMessage{" +
                "licensePlate='" + licensePlate + '\'' +
                ", logout=" + logout +
                ", devmode=" + devmode +
                ", deactivate=" + deactivate +
                ", appmode=" + appmode +
                ", simulate=" + simulate +
                '}';
    }
}


