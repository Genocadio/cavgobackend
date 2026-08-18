package com.gocavgo.delivary.repository.office;

import com.gocavgo.delivary.entity.office.OfficeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OfficeJpaRepository extends JpaRepository<OfficeEntity, UUID> {
}
