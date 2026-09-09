package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.entity.ClientUser;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.entity.User;
import com.nexxserve.cavgomain.enums.ClientType;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.enums.ContactMethod;
import com.nexxserve.cavgomain.enums.MembershipLevel;
import com.nexxserve.cavgomain.enums.UserStatus;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "userType", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = UserResponseDto.CompanyUserDto.class, name = "COMPANY"),
        @JsonSubTypes.Type(value = UserResponseDto.ClientUserDto.class, name = "CLIENT")
})
public abstract class UserResponseDto {
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private UserStatus status;
    private LocalDate dateOfBirth;
    private String address;
    private String createdAt;
    private String updatedAt;

    public static UserResponseDto fromEntity(User entity) {
        if (entity instanceof CompanyUser companyUser) {
            return CompanyUserDto.fromEntity(companyUser);
        } else if (entity instanceof ClientUser clientUser) {
            return ClientUserDto.fromEntity(clientUser);
        }
        return null;
    }

    @Data
    @EqualsAndHashCode(callSuper = false)
    public static class CompanyUserDto extends UserResponseDto {
        private Long companyId;
        private String companyName;
        private CompanyUserRole role;
        private String licenseNumber;
        private LocalDate licenseExpiry;
        private OfficeResponseDto office;

        public static CompanyUserDto fromEntity(CompanyUser entity) {
            CompanyUserDto dto = new CompanyUserDto();
            dto.setId(entity.getId());
            dto.setFirstName(entity.getFirstName());
            dto.setLastName(entity.getLastName());
            dto.setEmail(entity.getEmail());
            dto.setPhone(entity.getPhone());
            dto.setStatus(entity.getStatus());
            dto.setDateOfBirth(entity.getDateOfBirth());
            dto.setAddress(entity.getAddress());
            dto.setCreatedAt(entity.getCreatedAt().toString());
            dto.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
            if (entity.getCompany() != null) {
                dto.setCompanyId(entity.getCompany().getId());
                dto.setCompanyName(entity.getCompany().getCompanyName());
            }
            dto.setRole(entity.getRole());
            dto.setLicenseNumber(entity.getLicenseNumber());
            dto.setLicenseExpiry(entity.getLicenseExpiry());
            if (entity.getOffice() != null) {
                dto.setOffice(OfficeResponseDto.fromEntity(entity.getOffice()));
            }
            return dto;
        }
    }

    @Data
    @EqualsAndHashCode(callSuper = false)
    public static class ClientUserDto extends UserResponseDto {
        private ClientType clientType;
        private String companyName;
        private ContactMethod preferredContactMethod;
        private MembershipLevel membershipLevel;

        public static ClientUserDto fromEntity(ClientUser entity) {
            ClientUserDto dto = new ClientUserDto();
            dto.setId(entity.getId());
            dto.setFirstName(entity.getFirstName());
            dto.setLastName(entity.getLastName());
            dto.setEmail(entity.getEmail());
            dto.setPhone(entity.getPhone());
            dto.setStatus(entity.getStatus());
            dto.setDateOfBirth(entity.getDateOfBirth());
            dto.setAddress(entity.getAddress());
            dto.setCreatedAt(entity.getCreatedAt().toString());
            dto.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
            dto.setClientType(entity.getClientType());
            dto.setCompanyName(entity.getCompanyName());
            dto.setPreferredContactMethod(entity.getPreferredContactMethod());
            dto.setMembershipLevel(entity.getMembershipLevel());
            return dto;
        }
    }
}
