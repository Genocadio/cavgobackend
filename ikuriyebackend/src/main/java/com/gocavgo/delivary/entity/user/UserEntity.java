package com.gocavgo.delivary.entity.user;

import com.gocavgo.delivary.enums.user.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserEntity {

    @Id
    private Long id;

    @Version
    @Builder.Default
    private Long version = 0L;

    @Column(nullable = false, unique = true)
    private String email;

    private String phone;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(unique = true)
    private String username;

    @Column(name = "avatar_storage_path", columnDefinition = "TEXT")
    private String avatarStoragePath;

    @Column(name = "avatar_bucket", length = 64)
    private String avatarBucket;

    @Column(name = "avatar_storage_mode", length = 16)
    private String avatarStorageMode;

    /**
     * Opaque hash from the Nexxauth JWT {@code dataHash} claim. Changes on every
     * non-password user mutation in Nexxauth. The backend compares this to the
     * token's hash on each request to detect stale local data — only fetching
     * from Nexxauth when the hash is missing or different.
     */
    @Column(name = "data_hash", length = 64)
    private String dataHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
