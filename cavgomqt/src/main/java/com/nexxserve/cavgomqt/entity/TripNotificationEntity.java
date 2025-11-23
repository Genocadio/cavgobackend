package com.nexxserve.cavgomqt.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entity to track trip notifications that have been sent.
 * Used to prevent duplicate "about to complete" notifications.
 */
@Entity
@Table(name = "trip_notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TripNotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_id", nullable = false, unique = true)
    private Integer tripId;

    @Column(name = "vehicle_id", nullable = false)
    private Integer vehicleId;

    @Column(name = "license_plate", nullable = false)
    private String licensePlate;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @PrePersist
    protected void onCreate() {
        if (sentAt == null) {
            sentAt = LocalDateTime.now();
        }
    }

    @Override
    public String toString() {
        return "TripNotificationEntity{" +
                "id=" + id +
                ", tripId=" + tripId +
                ", vehicleId=" + vehicleId +
                ", licensePlate='" + licensePlate + '\'' +
                ", sentAt=" + sentAt +
                '}';
    }
}




