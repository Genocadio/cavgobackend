package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.entity.ClientUser;
import com.nexxserve.cavgomain.enums.ClientType;
import com.nexxserve.cavgomain.enums.ContactMethod;
import com.nexxserve.cavgomain.enums.MembershipLevel;
import com.nexxserve.cavgomain.enums.UserStatus;
import lombok.Data;

import java.time.LocalDate;

@Data
public class ClientUserResponseDto {
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private UserStatus status;
    private LocalDate dateOfBirth;
    private String address;
    private ClientType clientType;
    private String companyName;
    private ContactMethod preferredContactMethod;
    private MembershipLevel membershipLevel;
    private String createdAt;
    private String updatedAt;

    public ClientUserResponseDto toDto(ClientUser entity) {
        ClientUserResponseDto dto = new ClientUserResponseDto();
        dto.setId(entity.getId());
        dto.setFirstName(entity.getFirstName());
        dto.setLastName(entity.getLastName());
        dto.setEmail(entity.getEmail());
        dto.setPhone(entity.getPhone());
        dto.setStatus(entity.getStatus());
        dto.setDateOfBirth(entity.getDateOfBirth());
        dto.setAddress(entity.getAddress());
        dto.setClientType(entity.getClientType());
        dto.setCompanyName(entity.getCompanyName());
        dto.setPreferredContactMethod(entity.getPreferredContactMethod());
        dto.setMembershipLevel(entity.getMembershipLevel());
        dto.setCreatedAt(entity.getCreatedAt().toString());
        dto.setUpdatedAt(entity.getUpdatedAt().toString());
        return dto;
    }
}