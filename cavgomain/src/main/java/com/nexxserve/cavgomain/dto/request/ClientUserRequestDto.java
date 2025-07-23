package com.nexxserve.cavgomain.dto.request;

import com.nexxserve.cavgomain.entity.ClientUser;
import com.nexxserve.cavgomain.enums.ClientType;
import com.nexxserve.cavgomain.enums.ContactMethod;
import com.nexxserve.cavgomain.enums.MembershipLevel;
import com.nexxserve.cavgomain.enums.UserStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

@Data
public class ClientUserRequestDto {
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
    private ClientType clientType = ClientType.INDIVIDUAL;
    private String companyName;
    private ContactMethod preferredContactMethod = ContactMethod.EMAIL;
    private MembershipLevel membershipLevel = MembershipLevel.BASIC;

    public ClientUser toEntity() {
        ClientUser clientUser = new ClientUser();
        clientUser.setFirstName(this.firstName);
        clientUser.setLastName(this.lastName);
        clientUser.setEmail(this.email);
        clientUser.setPhone(this.phone);
        clientUser.setPassword(this.password);
        clientUser.setStatus(this.status);
        clientUser.setDateOfBirth(this.dateOfBirth);
        clientUser.setAddress(this.address);
        clientUser.setClientType(this.clientType);
        clientUser.setCompanyName(this.companyName);
        clientUser.setPreferredContactMethod(this.preferredContactMethod);
        clientUser.setMembershipLevel(this.membershipLevel);
        return clientUser;
    }
}