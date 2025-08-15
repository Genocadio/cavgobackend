package com.nexxserve.cavgomain.dto.request;

import com.nexxserve.cavgomain.entity.Vehicle;
import com.nexxserve.cavgomain.entity.Company;
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

    @NotNull(message = "Company ID is required")
    private String companyCode;

    @NotBlank(message = "Make is required")
    private String make;

    @NotBlank(message = "Model is required")
    private String model;

    @NotBlank(message = "Public key is required") //base 64 encoded
    private String pubKey;

    @Min(value = 1, message = "Capacity must be at least 1")
    private int capacity;

    @NotBlank(message = "License plate is required")
    private String licensePlate;

    @NotNull(message = "Vehicle type is required")
    private VehicleType vehicleType;

    private VehicleStatus status = VehicleStatus.AVAILABLE;

    public Vehicle toEntity(Company company) {
        Vehicle vehicle = new Vehicle();
        vehicle.setCompany(company);
        vehicle.setMake(this.make);
        vehicle.setModel(this.model);
        vehicle.setPubKey(this.pubKey);
        vehicle.setCapacity(this.capacity);
        vehicle.setLicensePlate(this.licensePlate);
        vehicle.setVehicleType(this.vehicleType);
        vehicle.setStatus(this.status);
        return vehicle;
    }
}