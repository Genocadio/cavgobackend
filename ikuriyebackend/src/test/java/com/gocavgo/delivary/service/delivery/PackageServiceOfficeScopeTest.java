package com.gocavgo.delivary.service.delivery;

import com.gocavgo.delivary.entity.delivery.PackageEntity;
import com.gocavgo.delivary.entity.delivery.PackageLocationEntity;
import com.gocavgo.delivary.enums.delivery.CustodianRole;
import com.gocavgo.delivary.enums.delivery.LocationType;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.enums.delivery.SortOrder;
import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.repository.delivery.PackageCustodianJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageLocationJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackagePersonJpaRepository;
import com.gocavgo.delivary.repository.transfer.TransferJpaRepository;
import com.gocavgo.delivary.repository.transfer.TransferPackageJpaRepository;
import com.gocavgo.delivary.service.user.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackageServiceOfficeScopeTest {

    private static final Long WORKER_ID = 12L;
    private static final UUID MY_OFFICE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_OFFICE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock private PackageJpaRepository packageRepo;
    @Mock private PackageLocationJpaRepository locationRepo;
    @Mock private PackagePersonJpaRepository personRepo;
    @Mock private PackageCustodianJpaRepository custodianRepo;
    @Mock private TransferJpaRepository transferRepo;
    @Mock private TransferPackageJpaRepository transferPackageRepo;
    @Mock private UserService userService;

    @InjectMocks private PackageService service;

    private void stubOfficeCustody() {
        when(userService.getCompanyIdForUser(WORKER_ID)).thenReturn(MY_OFFICE);
        when(custodianRepo.findCurrentCustodianPackageIdsByRole(CustodianRole.OFFICE))
                .thenReturn(List.of(atMyOffice(), atOtherOffice(), legacyPackage()));
        when(locationRepo.findPackageIdsByTypeAndOfficeLocationIdIn(
                LocationType.DESTINATION, List.of(MY_OFFICE)))
                .thenReturn(List.of(atMyOffice()));
        when(locationRepo.findPackageIdsByTypeAndOfficeLocationIdIn(
                LocationType.ORIGIN, List.of(MY_OFFICE)))
                .thenReturn(List.of());
        when(locationRepo.findByPackageIdIn(any())).thenReturn(List.of(
                location(atMyOffice(), MY_OFFICE),
                location(atOtherOffice(), OTHER_OFFICE),
                location(legacyPackage(), null)));
    }

    @Test
    void workerMyPackagesOnlyIncludesWorkerOfficeAndOfficeAgnosticCustody() {
        stubOfficeCustody();
        when(custodianRepo.findCurrentCustodianPackageIdsByUserId(WORKER_ID)).thenReturn(List.of());
        when(packageRepo.findAllById(any())).thenReturn(List.of());

        service.getMyPackages(WORKER_ID, null, Role.WORKER, null, SortOrder.DESC, 0, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(packageRepo).findAllById(idsCaptor.capture());
        assertEquals(Set.of(atMyOffice(), legacyPackage()), Set.copyOf(idsCaptor.getValue()));
    }

    @Test
    void workerSyncOnlyIncludesWorkerOfficeAndOfficeAgnosticCustody() {
        stubOfficeCustody();

        service.getPackagesModifiedSince(WORKER_ID, null, Role.WORKER, MY_OFFICE, java.time.Instant.EPOCH);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(packageRepo).findByIdInAndUpdatedAtGreaterThanEqual(idsCaptor.capture(), any());
        assertEquals(Set.of(atMyOffice(), legacyPackage()), Set.copyOf(idsCaptor.getValue()));
    }

    @Test
    void workerSyncOnlyIncludesCreatedPackagesFromTheWorkersOffice() {
        var since = Instant.parse("2026-01-01T00:00:00Z");
        var mine = UUID.fromString("44444444-4444-4444-4444-444444444444");
        var elsewhere = UUID.fromString("55555555-5555-5555-5555-555555555555");
        when(userService.getCompanyIdForUser(WORKER_ID)).thenReturn(MY_OFFICE);
        when(locationRepo.findPackageIdsByTypeAndOfficeLocationIdIn(
                LocationType.ORIGIN, List.of(MY_OFFICE)))
                .thenReturn(List.of(mine));
        when(packageRepo.findByStatusAndUpdatedAtGreaterThanEqual(PackageStatus.CREATED, since))
                .thenReturn(List.of(created(mine), created(elsewhere)));
        when(packageRepo.findByIdInAndUpdatedAtGreaterThanEqual(any(), eq(since)))
                .thenReturn(List.of());

        service.getPackagesModifiedSince(WORKER_ID, null, Role.WORKER, null, since);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(packageRepo).findByIdInAndUpdatedAtGreaterThanEqual(idsCaptor.capture(), eq(since));
        assertEquals(Set.of(mine), Set.copyOf(idsCaptor.getValue()));
    }

    private static UUID atMyOffice() {
        return UUID.fromString("11111111-1111-1111-1111-111111111111");
    }

    private static UUID atOtherOffice() {
        return UUID.fromString("22222222-2222-2222-2222-222222222222");
    }

    private static UUID legacyPackage() {
        return UUID.fromString("33333333-3333-3333-3333-333333333333");
    }

    private static PackageEntity created(UUID id) {
        return PackageEntity.builder()
                .id(id)
                .status(PackageStatus.CREATED)
                .updatedAt(Instant.EPOCH)
                .build();
    }

    private static PackageLocationEntity location(UUID packageId, UUID officeLocationId) {
        return PackageLocationEntity.builder()
                .packageId(packageId)
                .type(LocationType.DESTINATION)
                .officeLocationId(officeLocationId)
                .build();
    }
}
