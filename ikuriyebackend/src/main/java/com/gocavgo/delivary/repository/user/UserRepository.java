package com.gocavgo.delivary.repository.user;

import com.gocavgo.delivary.entity.user.UserEntity;

import java.util.List;
import java.util.Optional;

public interface UserRepository {
    UserEntity save(UserEntity user);
    Optional<UserEntity> findById(Long id);
    Optional<UserEntity> findByEmail(String email);
    List<UserEntity> findAll();
    List<UserEntity> searchUsers(String query);
    List<UserEntity> searchUsersByIds(List<Long> userIds, String query);
    boolean existsByEmail(String email);
    Optional<UserEntity> findByPhone(String phone);
    void deleteById(Long id);
}
