package com.nexxserve.cavgomain.repository;


import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.enums.CompanyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompanyRepository extends JpaRepository<Company, Long> {
    List<Company> findByStatus(CompanyStatus status);
    List<Company> findByCompanyNameContainingIgnoreCase(String name);
    Boolean existsByCompanyName(String companyName);

    boolean existsByCompanyCode(String companyCode);

    Optional<Company> findByCompanyCode(String companyCode);

    @Query("SELECT c FROM Company c WHERE c.email = :email")
    Optional<Company> findByEmail(@Param("email") String email);
}

