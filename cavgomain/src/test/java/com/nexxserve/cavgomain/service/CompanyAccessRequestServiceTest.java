package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.entity.CompanyAccessRequest;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.enums.CompanyAccessRequestStatus;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.repository.CompanyAccessRequestRepository;
import com.nexxserve.cavgomain.repository.CompanyRepository;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
import com.nexxserve.cavgomain.repository.DriverRequestRepository;
import com.nexxserve.cavgomain.security.NexxauthClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyAccessRequestServiceTest {

    @Mock
    private CompanyAccessRequestRepository requestRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private CompanyUserRepository companyUserRepository;
    @Mock
    private DriverRequestRepository driverRequestRepository;
    @Mock
    private AggregatorSyncService aggregatorSyncService;
    @Mock
    private NexxauthClient nexxauthClient;

    @InjectMocks
    private CompanyAccessRequestService companyAccessRequestService;

    @Test
    void approveRequest_replacesNexxauthRolesWithFleetManagerRole() {
        Long requestId = 20L;
        Long requesterUserId = 100L;
        Long approverUserId = 200L;

        Company company = new Company();
        company.setId(1L);
        company.setCompanyCode("COMP1");

        CompanyAccessRequest request = new CompanyAccessRequest();
        request.setId(requestId);
        request.setNexxauthUserId(requesterUserId);
        request.setCompany(company);
        request.setRole(CompanyUserRole.FLEET_MANAGER);
        request.setStatus(CompanyAccessRequestStatus.PENDING);
        request.setFirstName("Alice");
        request.setLastName("Smith");

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));
        when(requestRepository.save(any(CompanyAccessRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(companyUserRepository.findById(requesterUserId)).thenReturn(Optional.empty());

        var response = companyAccessRequestService.approveRequest(requestId, approverUserId);

        assertNotNull(response);
        assertEquals(CompanyAccessRequestStatus.APPROVED, request.getStatus());
        assertEquals(approverUserId, request.getApprovedBy());

        // Verify Nexxauth roles were updated directly to List.of("fleet_manager"), replacing customer/previous roles
        verify(nexxauthClient).updateUserRoles(requesterUserId, List.of("fleet_manager"));

        // Verify local CompanyUser created with FLEET_MANAGER role
        verify(companyUserRepository).save(argThat(user ->
                user.getId().equals(requesterUserId) &&
                        user.getRole() == CompanyUserRole.FLEET_MANAGER &&
                        user.getCompany().getId().equals(1L)
        ));
    }
}
