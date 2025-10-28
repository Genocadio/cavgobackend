package com.nexxserve.cavgomain.repository;

import com.nexxserve.cavgomain.entity.ClientUser;
import com.nexxserve.cavgomain.enums.ClientType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientUserRepository extends JpaRepository<ClientUser, Long> {
    List<ClientUser> findByClientType(ClientType clientType);
    
    @Query("SELECT cu FROM ClientUser cu WHERE cu.email = :email")
    Optional<ClientUser> findByEmail(@Param("email") String email);
    
    List<ClientUser> findByCompanyNameContainingIgnoreCase(String companyName);
}