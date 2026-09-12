package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.entity.DriverRequest;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.enums.DriverRequestStatus;
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
class DriverRequestServiceTest {

    @Mock
    private DriverRequestRepository driverRequestRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private CompanyUserRepository companyUserRepository;
    @Mock
    private CompanyAccessRequestRepository companyAccessRequestRepository;
    @Mock
    private NexxauthClient nexxauthClient;

    @InjectMocks
    private DriverRequestService driverRequestService;

    @Test
    void approveRequest_replacesNexxauthRolesWithDriverRole() {
        Long requestId = 10L;
        Long requesterUserId = 100L;
        Long approverUserId = 200L;

        Company company = new Company();
        company.setId(1L);
        company.setCompanyCode("COMP1");

        DriverRequest request = new DriverRequest();
        request.setId(requestId);
        request.setNexxauthUserId(requesterUserId);
        request.setCompany(company);
        request.setStatus(DriverRequestStatus.PENDING);
        request.setFirstName("John");
        request.setLastName("Doe");

        when(driverRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
        when(companyUserRepository.findById(requesterUserId)).thenReturn(Optional.empty());

        var response = driverRequestService.approveRequest(requestId, approverUserId);

        assertNotNull(response);
        assertEquals(DriverRequestStatus.APPROVED, request.getStatus());
        assertEquals(approverUserId, request.getApprovedBy());

        // Verify Nexxauth roles were updated directly to List.of("driver"), replacing customer/previous roles
        verify(nexxauthClient).updateUserRoles(requesterUserId, List.of("driver"));

        // Verify local CompanyUser created with DRIVER role
        verify(companyUserRepository).save(argThat(user ->
                user.getId().equals(requesterUserId) &&
                        user.getRole() == CompanyUserRole.DRIVER &&
                        user.getCompany().getId().equals(1L)
        ));
    }
}
