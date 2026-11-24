package com.nexxserve.cavgomqt.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Latest authoritative driver configuration pushed to Android devices.
 *
 * contentHash excludes timestamps so a periodic reconcile does not bump the
 * revision when nothing meaningful changed.
 */
@Entity
@Table(name = "driver_states", indexes = {
        @Index(name = "idx_driver_state_driver", columnList = "driver_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DriverStateEntity {

    @Id
    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "revision", nullable = false)
    private Long revision = 0L;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "reason")
    private String reason;

    @Column(name = "role")
    private String role;

    @Column(name = "user_status")
    private String userStatus;

    @Column(name = "session_state")
    private String sessionState;

    @Column(name = "updated_at")
    private Long updatedAt;

    @Column(name = "next_publish_at")
    private Long nextPublishAt;

    @Column(name = "last_published_revision")
    private Long lastPublishedRevision = 0L;

    @Column(name = "retry_attempt")
    private Integer retryAttempt = 0;
}
