package com.nexxserve.cavgomain.repository;

import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompanyUserRepository extends JpaRepository<CompanyUser, Long> {
    List<CompanyUser> findByCompanyId(Long companyId);
    Optional<CompanyUser> findByEmail(String email);
    List<CompanyUser> findByRole(CompanyUserRole role);
    List<CompanyUser> findByCompanyIdAndRole(Long companyId, CompanyUserRole role);

    @Query("SELECT cu FROM CompanyUser cu WHERE cu.company.id = :companyId AND cu.role = :role")
    List<CompanyUser> findDriversByCompany(@Param("companyId") Long companyId, @Param("role") CompanyUserRole role);

    @Query("SELECT cu FROM CompanyUser cu WHERE cu.licenseExpiry < CURRENT_DATE")
    List<CompanyUser> findUsersWithExpiredLicense();
}