package com.gocavgo.Navigation.model;

import com.gocavgo.Navigation.model.enums.TripStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "trips")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Trip {
    @Id
    @org.hibernate.annotations.GenericGenerator(name = "trip_id_gen", type = com.gocavgo.Navigation.model.TripIdGenerator.class, parameters = {
            @org.hibernate.annotations.Parameter(name = "sequence_name", value = "trips_id_seq"),
            @org.hibernate.annotations.Parameter(name = "increment_size", value = "1")
    })
    @GeneratedValue(generator = "trip_id_gen", strategy = GenerationType.SEQUENCE)
    private Long id; // Optional: can be set manually, otherwise auto-generated

    @Column(nullable = false)
    private String carId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripStatus status;

    // Waypoints: [lat, lon] pairs stored as JSON
    @Column(columnDefinition = "TEXT")
    private String waypointsJson;

    // Route data stored as JSON (immutable)
    @Column(columnDefinition = "TEXT")
    private String routeJson;

    @Column(nullable = false)
    private boolean includeOrigin;

    @Column(nullable = false)
    private boolean isCityTrip;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    // Transient fields (not persisted, computed from routeJson)
    @Transient
    private List<WaypointProgress> waypointProgresses;
}
