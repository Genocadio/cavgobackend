package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.enums.UserStatus;
import lombok.Data;

import java.time.LocalDate;

@Data
public class CompanyUserResponseDto {
    private Long id;
    private Long companyId;
    private String companyName;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private UserStatus status;
    private LocalDate dateOfBirth;
    private String address;
    private CompanyUserRole role;
    private String licenseNumber;
    private LocalDate licenseExpiry;
    private String createdAt;
    private String updatedAt;
    private VehicleResponseDto vehicle;
    private OfficeResponseDto office;

    public static CompanyUserResponseDto fromEntity(CompanyUser entity) {
        CompanyUserResponseDto dto = new CompanyUserResponseDto();
        dto.setId(entity.getId());
        dto.setCompanyId(entity.getCompany().getId());
        dto.setCompanyName(entity.getCompany().getCompanyName());
        dto.setFirstName(entity.getFirstName());
        dto.setLastName(entity.getLastName());
        dto.setEmail(entity.getEmail());
        dto.setPhone(entity.getPhone());
        dto.setStatus(entity.getStatus());
        dto.setDateOfBirth(entity.getDateOfBirth());
        dto.setAddress(entity.getAddress());
        dto.setRole(entity.getRole());
        dto.setLicenseNumber(entity.getLicenseNumber());
        dto.setLicenseExpiry(entity.getLicenseExpiry());
        dto.setCreatedAt(entity.getCreatedAt().toString());
        dto.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        if (entity.getOffice() != null) {
            dto.setOffice(OfficeResponseDto.fromEntity(entity.getOffice()));
        }
        return dto;
    }
}