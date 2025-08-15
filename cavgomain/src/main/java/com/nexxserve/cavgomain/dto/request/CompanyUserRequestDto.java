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

    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    private String email;

    private String phone;

    @NotBlank(message = "Password is required")
    private String password;

    private UserStatus status = UserStatus.ACTIVE;

    private LocalDate dateOfBirth;

    private String address;

    private CompanyUserRole role;

    private String licenseNumber;

    private LocalDate licenseExpiry;

    public CompanyUser toEntity(Company company) {
        CompanyUser user = new CompanyUser();
        user.setCompany(company);
        user.setFirstName(this.firstName);
        user.setLastName(this.lastName);
        user.setEmail(this.email);
        user.setPhone(this.phone);
        user.setPassword(this.password);
        user.setStatus(this.status);
        user.setDateOfBirth(this.dateOfBirth);
        user.setAddress(this.address);
        user.setRole(this.role);
        user.setLicenseNumber(this.licenseNumber);
        user.setLicenseExpiry(this.licenseExpiry);
        return user;
    }
}