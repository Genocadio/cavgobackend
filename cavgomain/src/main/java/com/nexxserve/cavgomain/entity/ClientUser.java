package com.nexxserve.cavgomain.entity;

import com.nexxserve.cavgomain.enums.ClientType;
import com.nexxserve.cavgomain.enums.ContactMethod;
import com.nexxserve.cavgomain.enums.MembershipLevel;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ClientUser extends User {

    @Column(name = "client_type")
    @Enumerated(EnumType.STRING)
    private ClientType clientType = ClientType.INDIVIDUAL;

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "preferred_contact_method")
    @Enumerated(EnumType.STRING)
    private ContactMethod preferredContactMethod = ContactMethod.EMAIL;

    @Column(name = "membership_level")
    @Enumerated(EnumType.STRING)
    private MembershipLevel membershipLevel = MembershipLevel.BASIC;
}
