package com.gocavgo.ussdservice.repository;


import com.gocavgo.ussdservice.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, Long> {
    Optional<UserSession> findByPhoneNumber(String phoneNumber);
    Optional<UserSession> findBySessionId(String sessionId);
    void deleteByPhoneNumber(String phoneNumber);
}