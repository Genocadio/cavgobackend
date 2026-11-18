package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.entity.CompanyAdminAccessRequest;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.enums.CompanyAdminAccessRequestStatus;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.repository.CompanyAdminAccessRequestRepository;
import com.nexxserve.cavgomain.repository.CompanyRepository;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
import com.nexxserve.cavgomain.security.NexxauthClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyAdminAccessRequestServiceTest {

    @Mock
    private CompanyAdminAccessRequestRepository requestRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private CompanyUserRepository companyUserRepository;

    @Mock
    private NexxauthClient nexxauthClient;

    @InjectMocks
    private CompanyAdminAccessRequestService service;

    @Test
    void isCompanyCodeValidRejectsBlankAndUnknownCodes() {
        when(companyRepository.findByCompanyCode("UNKNOWN")).thenReturn(Optional.empty());

        assertFalse(service.isCompanyCodeValid(" "));
        assertFalse(service.isCompanyCodeValid("unknown"));
    }

    @Test
    void isCompanyCodeValidAcceptsCaseInsensitiveCode() {
        when(companyRepository.findByCompanyCode("CAV123")).thenReturn(Optional.of(company()));

        assertTrue(service.isCompanyCodeValid(" cav123 "));
    }

    @Test
    void createRequestStoresPendingRequestForNormalizedCompanyCode() {
        var company = company();
        when(companyRepository.findByCompanyCode("CAV123")).thenReturn(Optional.of(company));
        when(requestRepository.existsByNexxauthUserIdAndCompanyId(42L, 7L)).thenReturn(false);
        when(nexxauthClient.getUser(42L)).thenReturn(new NexxauthClient.OrgUser(
                42L, "Ava", "Moyo", "ava@example.com", "0770000000", "ava", true, java.util.List.of(), java.util.List.of()));

        service.createRequest(" cav123 ", 42L);

        verify(requestRepository).save(argThat(request ->
                request.getNexxauthUserId().equals(42L)
                        && request.getCompanyCode().equals("CAV123")
                        && request.getCompany().equals(company)
                        && request.getFirstName().equals("Ava")
                        && request.getStatus() == CompanyAdminAccessRequestStatus.PENDING));
    }

    @Test
    void createRequestIsIdempotentForSameUserAndCompany() {
        when(companyRepository.findByCompanyCode("CAV123")).thenReturn(Optional.of(company()));
        when(requestRepository.existsByNexxauthUserIdAndCompanyId(42L, 7L)).thenReturn(true);

        service.createRequest("CAV123", 42L);

        verify(requestRepository, never()).save(any(CompanyAdminAccessRequest.class));
        verifyNoInteractions(nexxauthClient);
    }

    @Test
    void createRequestRejectsUnknownCompanyCode() {
        when(companyRepository.findByCompanyCode("UNKNOWN")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.createRequest("UNKNOWN", 42L));
        verify(requestRepository, never()).save(any(CompanyAdminAccessRequest.class));
    }

    @Test
    void approvalGrantsAdminRoleAndCreatesCompanyMembership() {
        var request = pendingRequest();
        when(requestRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(request));
        when(companyUserRepository.findById(42L)).thenReturn(Optional.empty());
        when(nexxauthClient.getUser(42L)).thenReturn(new NexxauthClient.OrgUser(
                42L, "Ava", "Moyo", "ava@example.com", "0770000000", "ava", true, java.util.List.of("customer"), java.util.List.of()));
        when(requestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.approve(7L, 3L, 10L);

        verify(nexxauthClient).updateUserRoles(42L, java.util.List.of("admin"));
        verify(companyUserRepository).save(argThat(user ->
                user.getId().equals(42L)
                        && user.getCompany().getId().equals(7L)
                        && user.getRole() == CompanyUserRole.ADMIN));
        assertEquals(CompanyAdminAccessRequestStatus.APPROVED, request.getStatus());
        assertEquals(10L, request.getApprovedBy());
    }

    @Test
    void approvalRejectsExistingMembershipInAnotherCompanyBeforeGrantingRoles() {
        var request = pendingRequest();
        CompanyUser existing = new CompanyUser();
        existing.setCompany(company(8L));
        when(requestRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(request));
        when(companyUserRepository.findById(42L)).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> service.approve(7L, 3L, 10L));
        verifyNoInteractions(nexxauthClient);
    }

    @Test
    void repeatedApprovalIsIdempotent() {
        var request = pendingRequest();
        request.setStatus(CompanyAdminAccessRequestStatus.APPROVED);
        when(requestRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(request));

        assertEquals(CompanyAdminAccessRequestStatus.APPROVED, service.approve(7L, 3L, 10L).getStatus());
        verifyNoInteractions(nexxauthClient, companyUserRepository);
    }

    private Company company() {
        return company(7L);
    }

    private Company company(Long id) {
        var company = new Company();
        company.setId(id);
        company.setCompanyCode("CAV123");
        return company;
    }

    private CompanyAdminAccessRequest pendingRequest() {
        var request = new CompanyAdminAccessRequest();
        request.setId(3L);
        request.setNexxauthUserId(42L);
        request.setCompanyCode("CAV123");
        request.setCompany(company());
        request.setStatus(CompanyAdminAccessRequestStatus.PENDING);
        return request;
    }
}
