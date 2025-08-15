package com.gocavgo.ussdservice.entity;

import com.gocavgo.ussdservice.dto.TripBookingOption;
import com.gocavgo.ussdservice.dto.TripDto;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "user_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String phoneNumber;

    @Column
    private String sessionId;

    @Column
    private String language;

    @Column
    private String currentStep;

    @Column
    private String origin;

    @Column
    private String destination;

    @ElementCollection
    @CollectionTable(name = "user_session_booking_options",
            joinColumns = @JoinColumn(name = "user_session_id"))
    private List<TripBookingOption> bookingOptions;

    @Transient // Not persisted to database
    private List<TripDto> availableTrips;

    @Column
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}