package com.nexxserve.cavgomain;

import com.nexxserve.cavgomain.dto.response.CompanyUserResponseDto;
import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.enums.UserStatus;
import com.nexxserve.cavgomain.repository.CompanyRepository;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
import com.nexxserve.cavgomain.security.NexxauthClient;
import com.nexxserve.cavgomain.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Regression test for the inline-sync provisioning path (see
 * {@link com.nexxserve.cavgomain.security.NexxauthJwtAuthenticationFilter} +
 * {@link UserService}): a brand-new CompanyUser whose id is pre-assigned (the
 * Nexxauth org-user id) must be INSERTed, not treated as a detached/updated
 * row. Previously this failed with Hibernate's
 * "Row was updated or deleted by another transaction (or unsaved-value mapping
 * was incorrect)" StaleStateException.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(UserService.class)
class CompanyUserPersistenceTest {

    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private CompanyUserRepository companyUserRepository;
    @Autowired
    private UserService userService;

    @MockitoBean
    private NexxauthClient nexxauthClient;

    @Test
    void syncUser_createsNewCompanyUser_withPresetNexxauthId() {
        long nexxauthUserId = 999L;
        var nexxauthUser = new NexxauthClient.OrgUser(
                nexxauthUserId, "John", "Doe", "john@test.com", "+250700000001",
                "johndoe", true, List.of("admin"), List.of("password"));
        when(nexxauthClient.getUser(nexxauthUserId)).thenReturn(nexxauthUser);

        CompanyUserResponseDto result = userService.syncUser(nexxauthUserId);

        assertThat(result.getId()).isEqualTo(nexxauthUserId);
        assertThat(result.getFirstName()).isEqualTo("John");
        assertThat(result.getRole()).isEqualTo(CompanyUserRole.ADMIN);
        assertThat(result.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(companyUserRepository.findById(nexxauthUserId)).isPresent();
    }

    @Test
    void saveCompanyUser_withPresetId_ignoresCompanyAssignment() {
        var company = new Company();
        company.setCompanyName("Cavgo test");
        company.setCompanyCode("CVT123");
        var savedCompany = companyRepository.saveAndFlush(company);

        // Staff creation (createCompanyUser) also uses a pre-assigned id and a
        // real company link. Direct repository save must INSERT.
        var user = new com.nexxserve.cavgomain.entity.CompanyUser();
        user.setId(1001L);
        user.setCompany(savedCompany);
        user.setFirstName("Jane");
        user.setLastName("Smith");
        user.setEmail("jane@test.com");
        user.setPhone("+250700000002");
        user.setRole(CompanyUserRole.DRIVER);
        user.setStatus(UserStatus.ACTIVE);

        var saved = companyUserRepository.saveAndFlush(user);

        assertThat(saved.getId()).isEqualTo(1001L);
        assertThat(companyUserRepository.findById(1001L)).isPresent();
        assertThat(saved.getCompany().getCompanyName()).isEqualTo("Cavgo test");
    }
}