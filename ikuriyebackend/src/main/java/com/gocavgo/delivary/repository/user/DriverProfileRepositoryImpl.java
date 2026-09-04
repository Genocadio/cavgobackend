package com.gocavgo.delivary.repository.user;

import com.gocavgo.delivary.entity.user.DriverProfileEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class DriverProfileRepositoryImpl implements DriverProfileRepository {

    private final DriverProfileJpaRepository jpaRepository;

    public DriverProfileRepositoryImpl(DriverProfileJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public DriverProfileEntity save(DriverProfileEntity profile) {
        return jpaRepository.save(profile);
    }

    @Override
    public Optional<DriverProfileEntity> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<DriverProfileEntity> findByUserId(Long userId) {
        return jpaRepository.findByUserId(userId);
    }

    @Override
    public List<DriverProfileEntity> findByCompanyId(UUID companyId) {
        return jpaRepository.findByCompanyId(companyId);
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
    }

    @Override
    @Transactional
    public void touchLastSeen(Long userId) {
        jpaRepository.touchLastSeen(userId, Instant.now());
    }

    @Override
    @Transactional
    public int markStaleDriversOffline(Instant threshold) {
        return jpaRepository.markStaleDriversOffline(threshold);
    }
}
