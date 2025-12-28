package com.gocavgo.Navigation.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "navigation_snapshots", indexes = {
    @Index(name = "idx_trip_timestamp", columnList = "tripId,snapshotTimestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NavigationSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private Long tripId;
    
    @Column(nullable = false)
    private String carId;
    
    @Column(nullable = false)
    private Instant snapshotTimestamp;
    
    @Column(columnDefinition = "TEXT")
    private String waypointProgressesJson; // JSON array of waypoint progress
    
    @Column(columnDefinition = "TEXT")
    private String currentLocationJson; // JSON of CurrentLocation
    
    @Column(nullable = false)
    private Double distanceTravelled;
    
    @Column(nullable = false)
    private Integer lastSnappedIndex;
}

