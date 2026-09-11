package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.entity.CompanyAccessRequest;
import com.nexxserve.cavgomain.enums.CompanyAccessRequestStatus;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Response DTO for a company access request — used by both the requesting
 * user and the fleet manager approving pending requests.
 */
@Data
public class CompanyAccessRequestResponseDto {

    private Long id;
    private Long nexxauthUserId;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private CompanyUserRole role;
    private String companyCode;
    private Long companyId;
    private String companyName;
    private CompanyAccessRequestStatus status;
    private String rejectionReason;
    private Long approvedBy;
    private Long rejectedBy;
    private String approvedAt;
    private String rejectedAt;
    private String createdAt;
    private String updatedAt;

    public static CompanyAccessRequestResponseDto fromEntity(CompanyAccessRequest entity) {
        CompanyAccessRequestResponseDto dto = new CompanyAccessRequestResponseDto();
        dto.setId(entity.getId());
        dto.setNexxauthUserId(entity.getNexxauthUserId());
        dto.setFirstName(entity.getFirstName());
        dto.setLastName(entity.getLastName());
        dto.setEmail(entity.getEmail());
        dto.setPhone(entity.getPhone());
        dto.setRole(entity.getRole());
        dto.setCompanyCode(entity.getCompanyCode());
        if (entity.getCompany() != null) {
            dto.setCompanyId(entity.getCompany().getId());
            dto.setCompanyName(entity.getCompany().getCompanyName());
        }
        dto.setStatus(entity.getStatus());
        dto.setRejectionReason(entity.getRejectionReason());
        dto.setApprovedBy(entity.getApprovedBy());
        dto.setRejectedBy(entity.getRejectedBy());
        dto.setApprovedAt(entity.getApprovedAt() != null ? entity.getApprovedAt().toString() : null);
        dto.setRejectedAt(entity.getRejectedAt() != null ? entity.getRejectedAt().toString() : null);
        dto.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        dto.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        return dto;
    }
}