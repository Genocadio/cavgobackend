package com.nexxserve.cavgomain.entity;

import com.nexxserve.cavgomain.enums.UserStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * User profile mirror — identity and authentication are managed by Nexxauth.
 * The {@code id} is the Nexxauth org-user id (provided externally, never
 * auto-generated). Passwords are not stored locally.
 */
@Entity
@Table(name = "users")
@Inheritance(strategy = InheritanceType.JOINED)
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public abstract class User extends BaseEntity {

    // Override BaseEntity: Nexxauth provides the ID externally, never auto-generate
    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "email")
    private String email;

    @Column(name = "phone")
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private UserStatus status = UserStatus.ACTIVE;

    /**
     * Opaque hash from the Nexxauth JWT — changes on every non-password user
     * mutation. Used by the backend to detect stale local data without hitting
     * Nexxauth on every request.
     */
    @Column(name = "data_hash")
    private String dataHash;

    @Column(name = "date_of_birth")
    private java.time.LocalDate dateOfBirth;

    @Column(name = "address")
    private String address;
}