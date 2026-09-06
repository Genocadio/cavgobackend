package com.gocavgo.delivary.repository.user;

import com.gocavgo.delivary.entity.user.UserEntity;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Component
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository jpaRepository;

    public UserRepositoryImpl(UserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public UserEntity save(UserEntity user) {
        return jpaRepository.save(user);
    }

    @Override
    public Optional<UserEntity> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<UserEntity> findAllById(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return jpaRepository.findAllById(ids);
    }

    @Override
    public Optional<UserEntity> findByEmail(String email) {
        return jpaRepository.findByEmail(email);
    }

    @Override
    public List<UserEntity> findAll() {
        return jpaRepository.findAll();
    }

    @Override
    public List<UserEntity> searchUsers(String query) {
        return jpaRepository.searchUsers(query);
    }

    @Override
    public List<UserEntity> searchUsersByIds(List<Long> userIds, String query) {
        return jpaRepository.searchUsersByIds(userIds, query);
    }

    @Override
    public boolean existsByEmail(String email) {
        return jpaRepository.existsByEmail(email);
    }

    @Override
    public Optional<UserEntity> findByPhone(String phone) {
        return jpaRepository.findByPhone(phone);
    }

    @Override
    public List<UserEntity> findByAvatarStorageMode(String storageMode) {
        return jpaRepository.findByAvatarStorageMode(storageMode);
    }

    @Override
    public void deleteById(Long id) {
        jpaRepository.deleteById(id);
    }
}
