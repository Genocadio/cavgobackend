package com.gocavgo.delivary.testsupport;

import com.gocavgo.delivary.entity.trip.CavgoTripEntity;
import com.gocavgo.delivary.repository.trip.CavgoTripJpaRepository;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-only helpers for the trip mirror. Package custody validation now requires
 * a DRIVER to have a real active trip; these helpers give fixture drivers one so
 * the existing acceptance/handoff tests keep exercising their original behaviour.
 */
public final class TripTestFixtures {

    private static final AtomicLong NEXT_TRIP_ID = new AtomicLong(9_000_000L);

    private TripTestFixtures() {
    }

    /** Creates an IN_PROGRESS trip for the driver, with a stable synthetic company id. */
    public static CavgoTripEntity createActiveTrip(CavgoTripJpaRepository repo, Long driverId) {
        return createActiveTrip(repo, driverId, 100L + (driverId % 1000));
    }

    public static CavgoTripEntity createActiveTrip(CavgoTripJpaRepository repo, Long driverId, Long companyId) {
        var trip = CavgoTripEntity.builder()
                .tripId(NEXT_TRIP_ID.getAndIncrement())
                .driverId(driverId)
                .companyId(companyId)
                .status("IN_PROGRESS")
                .event("started")
                .departureTime(Instant.now())
                .build();
        return repo.save(trip);
    }

    /** Creates a trip with an explicit status, for negative-path tests. */
    public static CavgoTripEntity createTrip(CavgoTripJpaRepository repo, Long driverId,
                                             Long companyId, String status) {
        var trip = CavgoTripEntity.builder()
                .tripId(NEXT_TRIP_ID.getAndIncrement())
                .driverId(driverId)
                .companyId(companyId)
                .status(status)
                .event("created")
                .departureTime(Instant.now())
                .build();
        return repo.save(trip);
    }
}
