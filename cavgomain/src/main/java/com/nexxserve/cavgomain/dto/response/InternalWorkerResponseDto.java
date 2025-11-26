package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.enums.UserStatus;
import lombok.Data;

@Data
public class InternalWorkerResponseDto {
    private String id;
    private String name; // firstName + lastName
    private String phone;
    private String email;
    private String licenseNumber;
    private String status; // UserStatus enum value
    private String role; // CompanyUserRole enum value (ADMIN, DRIVER, FLEET_MANAGER, SUPERVISOR)
    private InternalVehicleResponseDto vehicle; // assigned vehicle if exists, else null (only for DRIVER role)

    public static InternalWorkerResponseDto fromEntity(CompanyUser user, InternalVehicleResponseDto vehicle) {
        InternalWorkerResponseDto dto = new InternalWorkerResponseDto();
        dto.setId(user.getId().toString());
        dto.setName((user.getFirstName() != null ? user.getFirstName() : "") + 
                   (user.getLastName() != null ? " " + user.getLastName() : "").trim());
        dto.setPhone(user.getPhone());
        dto.setEmail(user.getEmail());
        dto.setLicenseNumber(user.getLicenseNumber());
        dto.setStatus(user.getStatus() != null ? user.getStatus().name() : UserStatus.ACTIVE.name());
        dto.setRole(user.getRole() != null ? user.getRole().name() : null);
        dto.setVehicle(vehicle);
        return dto;
    }
}
