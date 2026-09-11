package com.nexxserve.cavgomain.repository;

import com.nexxserve.cavgomain.entity.CompanyAccessRequest;
import com.nexxserve.cavgomain.enums.CompanyAccessRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CompanyAccessRequestRepository extends JpaRepository<CompanyAccessRequest, Long> {

    List<CompanyAccessRequest> findByCompanyIdOrderByCreatedAtDesc(Long companyId);

    List<CompanyAccessRequest> findByCompanyIdAndStatus(Long companyId, CompanyAccessRequestStatus status);

    Optional<CompanyAccessRequest> findByNexxauthUserIdAndStatus(Long nexxauthUserId, CompanyAccessRequestStatus status);

    Optional<CompanyAccessRequest> findTopByNexxauthUserIdOrderByCreatedAtDesc(Long nexxauthUserId);

    boolean existsByNexxauthUserIdAndStatus(Long nexxauthUserId, CompanyAccessRequestStatus status);

    @Query("SELECT r FROM CompanyAccessRequest r WHERE r.company.id = :companyId " +
           "AND (r.createdAt >= :timeLimit OR r.updatedAt >= :timeLimit)")
    List<CompanyAccessRequest> findByCompanyIdAfterTime(@Param("companyId") Long companyId,
                                                        @Param("timeLimit") LocalDateTime timeLimit);

}