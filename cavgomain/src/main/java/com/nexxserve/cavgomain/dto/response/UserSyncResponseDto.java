package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.enums.UserStatus;
import lombok.Data;

import java.time.LocalDate;

/**
 * Response for POST /main/users/sync — the single profile entry point used by
 * ikuriye, ikuriyeweb and fleetman.
 *
 * <p>Structured as two nested objects so clients can branch on membership:
 * <ul>
 *   <li>{@code user} — identity + role (always present; the user is mirrored
 *       from Nexxauth on every sync).</li>
 *   <li>{@code company} — the user's company context, or {@code null} when they
 *       are not yet a company member. Includes company details plus the nested
 *       {@code office} and {@code vehicle} objects when applicable.</li>
 * </ul>
 */
@Data
public class UserSyncResponseDto {

    private UserInfo user;
    private CompanyContext company;

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
        private VehicleResponseDto vehicle;
        private OfficeResponseDto office;
    }

    public static UserSyncResponseDto fromCompanyUser(CompanyUser user,
                                                      VehicleResponseDto vehicle) {
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

        if (user.getCompany() != null) {
            CompanyContext company = new CompanyContext();
            company.setCompanyId(user.getCompany().getId());
            company.setCompanyName(user.getCompany().getCompanyName());
            company.setCompanyCode(user.getCompany().getCompanyCode());
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