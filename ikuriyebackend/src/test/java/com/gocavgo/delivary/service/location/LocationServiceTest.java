package com.gocavgo.delivary.service.location;

import com.gocavgo.delivary.dto.location.input.CreateLocationInput;
import com.gocavgo.delivary.entity.delivery.PackageLocationEntity;
import com.gocavgo.delivary.enums.delivery.LocationType;
import com.gocavgo.delivary.repository.delivery.PackageLocationJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocationServiceTest {

    private PackageLocationJpaRepository locationRepo;
    private LocationService locationService;

    @BeforeEach
    void setUp() {
        locationRepo = mock(PackageLocationJpaRepository.class);
        locationService = new LocationService(locationRepo);
    }

    @Test
    void createStandaloneLocationIsNotTiedToAPackage() {
        when(locationRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var input = new CreateLocationInput(LocationType.ORIGIN, 1.23, 4.56, "HQ", "place-1");
        locationService.createStandaloneLocation(input);

        var captor = ArgumentCaptor.forClass(PackageLocationEntity.class);
        verify(locationRepo).save(captor.capture());
        var saved = captor.getValue();
        assertNull(saved.getPackageId(), "standalone location must not reference a package");
        assertEquals(LocationType.ORIGIN, saved.getType());
        assertEquals(1.23, saved.getLatitude());
        assertEquals("HQ", saved.getPlaceName());
    }
}
