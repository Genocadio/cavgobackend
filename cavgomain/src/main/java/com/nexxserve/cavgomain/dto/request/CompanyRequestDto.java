package com.nexxserve.cavgomain.dto.request;

import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.enums.CompanyStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CompanyRequestDto {

    @NotBlank(message = "Company name is required")
    private String companyName;

    @Email(message = "Email should be valid")
    private String email;

    private String phone;

    private String address;

    private String city;

    private CompanyStatus status = CompanyStatus.ACTIVE;

    public Company toEntity() {
        Company company = new Company();
        company.setCompanyName(this.companyName);
        company.setEmail(this.email);
        company.setPhone(this.phone);
        company.setAddress(this.address);
        company.setCity(this.city);
        company.setStatus(this.status);
        return company;
    }
}