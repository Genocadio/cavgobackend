package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.entity.CompanyAccessRequest;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.enums.UserStatus;
import lombok.Data;

import java.time.LocalDate;

/**
 * Response for POST /main/users/sync — the single profile entry point used by
 * ikuriye, ikuriyeweb and fleetman for ANY current-user data that lives in the
 * /main namespace (cavgomain).
 *
 * <p>Structured as nested objects so clients can branch on membership:
 * <ul>
 *   <li>{@code user} — identity + role (always present; the user is mirrored
 *       from Nexxauth on every sync).</li>
 *   <li>{@code company} — the user's company context, or {@code null} when they
 *       are not yet a company member. Includes company details plus the nested
 *       {@code office} and {@code vehicle} objects when applicable.</li>
 *   <li>{@code accessRequest} — the latest company-access request for the user
 *       (non-driver onboarding), or {@code null}. Only queried when they have
 *       no company so pending/rejected requests survive a re-sync.</li>
 * </ul>
 */
@Data
public class UserSyncResponseDto {

    private UserInfo user;
    private CompanyContext company;
    private AccessRequestInfo accessRequest;

    @Data
    public static class UserInfo {
        private Long id;
        private String firstName;
        private String lastName;
        private String email;
        private String phone;
        private UserStatus status;
        private CompanyUserRole role;
        private LocalDate dateOfBirth;
        private String address;
        private String licenseNumber;
        private LocalDate licenseExpiry;
        private String createdAt;
        private String updatedAt;
    }

    @Data
    public static class CompanyContext {
        private Long companyId;
        private String companyName;
        private String companyCode;
        private String email;
        private String phone;
        private String address;
        private String city;
        private VehicleResponseDto vehicle;
        private OfficeResponseDto office;
    }

    @Data
    public static class AccessRequestInfo {
        private Long id;
        private String status;
        private String rejectionReason;
        private String companyCode;
        private Long companyId;
        private String companyName;
    }

    public static UserSyncResponseDto fromCompanyUser(CompanyUser user,
                                                      VehicleResponseDto vehicle,
                                                      CompanyAccessRequest accessRequest) {
        UserSyncResponseDto response = new UserSyncResponseDto();

        UserInfo info = new UserInfo();
        info.setId(user.getId());
        info.setFirstName(user.getFirstName());
        info.setLastName(user.getLastName());
        info.setEmail(user.getEmail());
        info.setPhone(user.getPhone());
        info.setStatus(user.getStatus());
        info.setRole(user.getRole());
        info.setDateOfBirth(user.getDateOfBirth());
        info.setAddress(user.getAddress());
        info.setLicenseNumber(user.getLicenseNumber());
        info.setLicenseExpiry(user.getLicenseExpiry());
        info.setCreatedAt(user.getCreatedAt() != null ? user.getCreatedAt().toString() : null);
        info.setUpdatedAt(user.getUpdatedAt() != null ? user.getUpdatedAt().toString() : null);
        response.setUser(info);

        if (accessRequest != null) {
            AccessRequestInfo req = new AccessRequestInfo();
            req.setId(accessRequest.getId());
            req.setStatus(String.valueOf(accessRequest.getStatus()));
            req.setRejectionReason(accessRequest.getRejectionReason());
            req.setCompanyCode(accessRequest.getCompanyCode());
            if (accessRequest.getCompany() != null) {
                req.setCompanyId(accessRequest.getCompany().getId());
                req.setCompanyName(accessRequest.getCompany().getCompanyName());
            }
            response.setAccessRequest(req);
        }

        if (user.getCompany() != null) {
            Company entity = user.getCompany();
            CompanyContext company = new CompanyContext();
            company.setCompanyId(entity.getId());
            company.setCompanyName(entity.getCompanyName());
            company.setCompanyCode(entity.getCompanyCode());
            company.setEmail(entity.getEmail());
            company.setPhone(entity.getPhone());
            company.setAddress(entity.getAddress());
            company.setCity(entity.getCity());
            if (user.getOffice() != null) {
                company.setOffice(OfficeResponseDto.fromEntity(user.getOffice()));
            }
            if (user.getRole() == CompanyUserRole.DRIVER) {
                company.setVehicle(vehicle);
            }
            response.setCompany(company);
        }

        return response;
    }
}