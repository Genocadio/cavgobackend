package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.entity.DriverRequest;
import com.nexxserve.cavgomain.enums.DriverRequestStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Response DTO for a driver request — used by both the requesting user
 * and the fleet manager viewing pending requests.
 */
@Data
public class DriverRequestResponseDto {
    private Long id;
    private Long nexxauthUserId;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String companyCode;
    private Long companyId;
    private String companyName;
    private DriverRequestStatus status;
    private String rejectionReason;
    private String createdAt;
    private String updatedAt;

    public static DriverRequestResponseDto fromEntity(DriverRequest entity) {
        DriverRequestResponseDto dto = new DriverRequestResponseDto();
        dto.setId(entity.getId());
        dto.setNexxauthUserId(entity.getNexxauthUserId());
        dto.setFirstName(entity.getFirstName());
        dto.setLastName(entity.getLastName());
        dto.setEmail(entity.getEmail());
        dto.setPhone(entity.getPhone());
        dto.setCompanyCode(entity.getCompanyCode());
        if (entity.getCompany() != null) {
            dto.setCompanyId(entity.getCompany().getId());
            dto.setCompanyName(entity.getCompany().getCompanyName());
        }
        dto.setStatus(entity.getStatus());
        dto.setRejectionReason(entity.getRejectionReason());
        dto.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        dto.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        return dto;
    }
}
