package com.gocavgo.delivary.repository.user;

import com.gocavgo.delivary.entity.user.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserJpaRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByEmail(String email);

    @Query("SELECT u FROM UserEntity u WHERE " +
           "(:query IS NULL OR LOWER(u.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(u.lastName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(u.username) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<UserEntity> searchUsers(@Param("query") String query);

    @Query("SELECT u FROM UserEntity u WHERE u.id IN :userIds AND " +
           "(:query IS NULL OR LOWER(u.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(u.lastName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(u.username) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<UserEntity> searchUsersByIds(@Param("userIds") List<Long> userIds,
                                       @Param("query") String query);

    boolean existsByEmail(String email);

    Optional<UserEntity> findByPhone(String phone);
    List<UserEntity> findByAvatarStorageMode(String storageMode);
}
