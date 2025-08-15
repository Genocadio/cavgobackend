package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.enums.CompanyStatus;
import lombok.Data;

@Data
public class CompanyResponseDto {
    private Long id;
    private String companyName;
    private String email;
    private String phone;
    private String address;
    private String city;
    private String companyCode;
    private CompanyStatus status;
    private String createdAt;
    private String updatedAt;
    private String createdBy;
    private String updatedBy;

    public static CompanyResponseDto fromEntity(Company entity) {
        CompanyResponseDto dto = new CompanyResponseDto();
        dto.setId(entity.getId());
        dto.setCompanyName(entity.getCompanyName());
        dto.setEmail(entity.getEmail());
        dto.setPhone(entity.getPhone());
        dto.setAddress(entity.getAddress());
        dto.setCity(entity.getCity());
        dto.setStatus(entity.getStatus());
        dto.setCompanyCode(entity.getCompanyCode());
        dto.setCreatedAt(entity.getCreatedAt().toString());
        dto.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        return dto;
    }
}