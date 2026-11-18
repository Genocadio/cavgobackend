package com.nexxserve.cavgomain.repository;

import com.nexxserve.cavgomain.entity.CompanyAdminAccessRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompanyAdminAccessRequestRepository extends JpaRepository<CompanyAdminAccessRequest, Long> {

    boolean existsByNexxauthUserIdAndCompanyId(Long nexxauthUserId, Long companyId);

    List<CompanyAdminAccessRequest> findByCompanyIdAndStatusOrderByCreatedAtDesc(
            Long companyId, com.nexxserve.cavgomain.enums.CompanyAdminAccessRequestStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT request FROM CompanyAdminAccessRequest request WHERE request.id = :id")
    Optional<CompanyAdminAccessRequest> findByIdForUpdate(@Param("id") Long id);
}
