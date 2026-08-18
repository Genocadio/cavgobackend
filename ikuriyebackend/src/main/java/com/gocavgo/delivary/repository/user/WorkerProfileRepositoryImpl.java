package com.gocavgo.delivary.repository.user;

import com.gocavgo.delivary.entity.user.WorkerProfileEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class WorkerProfileRepositoryImpl implements WorkerProfileRepository {

    private final WorkerProfileJpaRepository jpaRepository;

    public WorkerProfileRepositoryImpl(WorkerProfileJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public WorkerProfileEntity save(WorkerProfileEntity profile) {
        return jpaRepository.save(profile);
    }

    @Override
    public Optional<WorkerProfileEntity> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<WorkerProfileEntity> findByUserId(Long userId) {
        return jpaRepository.findByUserId(userId);
    }

    @Override
    public List<WorkerProfileEntity> findByCompanyId(UUID companyId) {
        return jpaRepository.findByCompanyId(companyId);
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
    }
}
