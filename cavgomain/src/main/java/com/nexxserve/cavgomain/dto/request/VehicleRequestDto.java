package com.nexxserve.cavgomain.dto.request;

import com.nexxserve.cavgomain.entity.Vehicle;
import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.enums.VehicleServiceType;
import com.nexxserve.cavgomain.enums.VehicleStatus;
import com.nexxserve.cavgomain.enums.VehicleType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

@Data
public class VehicleRequestDto {

    // Optional — when present the vehicle is linked to that company; otherwise
    // it is resolved from the authenticated user's company.
    private String companyCode;

    @NotBlank(message = "Make is required")
    private String make;

    @NotBlank(message = "Model is required")
    private String model;

    @NotBlank(message = "Public key is required") //base 64 encoded
    private String pubKey;

    @Min(value = 0, message = "Capacity must be at least 0")
    private int capacity;

    @NotBlank(message = "License plate is required")
    private String licensePlate;

    @NotNull(message = "Vehicle type is required")
    private VehicleType vehicleType;

    // Defaults to BOTH for backward compatibility with older clients.
    private VehicleServiceType serviceType = VehicleServiceType.PASSENGER_CARGO;

    private VehicleStatus status = VehicleStatus.AVAILABLE;

    public VehicleServiceType resolvedServiceType() {
        return serviceType != null ? serviceType : VehicleServiceType.PASSENGER_CARGO;
    }

    public int resolvedCapacity() {
        VehicleServiceType resolved = resolvedServiceType();
        if (resolved == VehicleServiceType.CARGO) {
            return 0;
        }
        return capacity;
    }

    public void validateForServiceType() {
        VehicleServiceType resolved = resolvedServiceType();
        if (resolved == VehicleServiceType.CARGO) {
            // Cargo-only vehicles are not bookable for passengers, so seats are always 0.
            return;
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be at least 1 for passenger-capable vehicles");
        }
    }

    public Vehicle toEntity(Company company) {
        Vehicle vehicle = new Vehicle();
        vehicle.setCompany(company);
        vehicle.setMake(this.make);
        vehicle.setModel(this.model);
        vehicle.setPubKey(this.pubKey);
        vehicle.setCapacity(this.resolvedCapacity());
        vehicle.setLicensePlate(this.licensePlate);
        vehicle.setVehicleType(this.vehicleType);
        vehicle.setServiceType(this.resolvedServiceType());
        vehicle.setStatus(this.status);
        return vehicle;
    }
}