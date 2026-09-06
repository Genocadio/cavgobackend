package com.nexxserve.cavgomain.dto.request;

import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.enums.UserStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class CompanyUserRequestDto {
    @NotNull(message = "Company ID is required")
    private String companyCode;

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @Email(message = "Email should be valid")
    private String email;
    @NotBlank(message = "Phone is required")
    private String phone;

    // Password removed — authentication is managed by Nexxauth

    private UserStatus status = UserStatus.ACTIVE;

    private LocalDate dateOfBirth;

    private String address;

    private CompanyUserRole role;

    private String licenseNumber;

    private LocalDate licenseExpiry;

    /** Nexxauth user ID to link this company user to an existing Nexxauth identity */
    private Long nexxauthUserId;

    /** Office ID to assign this worker to (optional — ADMIN can set, worker can self-assign) */
    private Long officeId;

    public CompanyUser toEntity(Company company) {
        CompanyUser user = new CompanyUser();
        user.setCompany(company);
        if (this.nexxauthUserId != null) {
            user.setId(this.nexxauthUserId);
        }
        user.setFirstName(this.firstName);
        user.setLastName(this.lastName);
        user.setEmail(this.email);
        user.setPhone(this.phone);
        user.setStatus(this.status);
        user.setDateOfBirth(this.dateOfBirth);
        user.setAddress(this.address);
        user.setRole(this.role);
        user.setLicenseNumber(this.licenseNumber);
        user.setLicenseExpiry(this.licenseExpiry);
        return user;
    }
}