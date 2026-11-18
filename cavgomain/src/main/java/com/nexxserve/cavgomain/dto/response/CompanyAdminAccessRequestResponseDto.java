package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.entity.CompanyAdminAccessRequest;
import com.nexxserve.cavgomain.enums.CompanyAdminAccessRequestStatus;
import lombok.Data;

@Data
public class CompanyAdminAccessRequestResponseDto {
    private Long id;
    private Long nexxauthUserId;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String companyCode;
    private Long companyId;
    private String companyName;
    private CompanyAdminAccessRequestStatus status;
    private Long approvedBy;
    private String approvedAt;
    private Long rejectedBy;
    private String rejectedAt;
    private String rejectionReason;
    private String createdAt;

    public static CompanyAdminAccessRequestResponseDto fromEntity(CompanyAdminAccessRequest entity) {
        var dto = new CompanyAdminAccessRequestResponseDto();
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
        dto.setApprovedBy(entity.getApprovedBy());
        dto.setApprovedAt(entity.getApprovedAt() == null ? null : entity.getApprovedAt().toString());
        dto.setRejectedBy(entity.getRejectedBy());
        dto.setRejectedAt(entity.getRejectedAt() == null ? null : entity.getRejectedAt().toString());
        dto.setRejectionReason(entity.getRejectionReason());
        dto.setCreatedAt(entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
        return dto;
    }
}
