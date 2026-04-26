package com.nexxserve.cavgomain.repository;

import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CompanyUserRepository extends JpaRepository<CompanyUser, Long> {
    List<CompanyUser> findByCompanyId(Long companyId);
    
    @Query("SELECT cu FROM CompanyUser cu WHERE cu.email = :email")
    Optional<CompanyUser> findByEmail(@Param("email") String email);
    
    @Query("SELECT cu FROM CompanyUser cu WHERE cu.phone = :phone")
    Optional<CompanyUser> findByPhone(@Param("phone") String phone);
    
    List<CompanyUser> findByRole(CompanyUserRole role);
    List<CompanyUser> findByCompanyIdAndRole(Long companyId, CompanyUserRole role);

    @Query("SELECT cu FROM CompanyUser cu WHERE cu.licenseExpiry < CURRENT_DATE")
    List<CompanyUser> findUsersWithExpiredLicense();

    @Query("SELECT cu FROM CompanyUser cu WHERE cu.company.id = :companyId " +
           "AND (cu.createdAt >= :timeLimit OR cu.updatedAt >= :timeLimit)")
    List<CompanyUser> findByCompanyIdAfterTime(@Param("companyId") Long companyId, @Param("timeLimit") LocalDateTime timeLimit);

    @Query(
           value = "SELECT cu FROM CompanyUser cu WHERE cu.company.id = :companyId " +
                  "AND cu.role = com.nexxserve.cavgomain.enums.CompanyUserRole.DRIVER " +
                  "AND (cu.createdAt >= :timeLimit OR cu.updatedAt >= :timeLimit) " +
                  "AND (:query IS NULL OR LOWER(cu.firstName) LIKE CONCAT('%', LOWER(CAST(:query AS string)), '%') " +
                  "OR LOWER(cu.lastName) LIKE CONCAT('%', LOWER(CAST(:query AS string)), '%') " +
                  "OR LOWER(CONCAT(cu.firstName, ' ', cu.lastName)) LIKE CONCAT('%', LOWER(CAST(:query AS string)), '%'))"
           ,
           countQuery = "SELECT COUNT(cu.id) FROM CompanyUser cu WHERE cu.company.id = :companyId " +
                  "AND cu.role = com.nexxserve.cavgomain.enums.CompanyUserRole.DRIVER " +
                  "AND (cu.createdAt >= :timeLimit OR cu.updatedAt >= :timeLimit) " +
                  "AND (:query IS NULL OR LOWER(cu.firstName) LIKE CONCAT('%', LOWER(CAST(:query AS string)), '%') " +
                  "OR LOWER(cu.lastName) LIKE CONCAT('%', LOWER(CAST(:query AS string)), '%') " +
                  "OR LOWER(CONCAT(cu.firstName, ' ', cu.lastName)) LIKE CONCAT('%', LOWER(CAST(:query AS string)), '%'))"
    )
    Page<CompanyUser> searchDriversByCompany(
           @Param("companyId") Long companyId,
           @Param("timeLimit") LocalDateTime timeLimit,
           @Param("query") String query,
           Pageable pageable
    );
}