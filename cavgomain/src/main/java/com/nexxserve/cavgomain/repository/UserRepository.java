package com.nexxserve.cavgomain.repository;

import com.nexxserve.cavgomain.entity.User;
import com.nexxserve.cavgomain.enums.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @Query("SELECT u FROM User u WHERE u.email = :email")
    Optional<User> findByEmail(@Param("email") String email);
    
    @Query("SELECT u FROM User u WHERE u.phone = :phone")
    Optional<User> findByPhone(@Param("phone") String phone);
    
    List<User> findByStatus(UserStatus status);
    boolean existsByEmail(String email);

    @Query("SELECT u FROM User u WHERE u.firstName LIKE %:name% OR u.lastName LIKE %:name%")
    List<User> findByNameContaining(@Param("name") String name);

    @Query("SELECT u FROM User u WHERE (u.createdAt >= :timeLimit OR u.updatedAt >= :timeLimit)")
    List<User> findAllAfterTime(@Param("timeLimit") LocalDateTime timeLimit);

    @Query("SELECT u FROM User u WHERE (u.firstName LIKE %:name% OR u.lastName LIKE %:name%) " +
           "AND (u.createdAt >= :timeLimit OR u.updatedAt >= :timeLimit)")
    List<User> findByNameContainingAfterTime(@Param("name") String name, @Param("timeLimit") LocalDateTime timeLimit);
}
