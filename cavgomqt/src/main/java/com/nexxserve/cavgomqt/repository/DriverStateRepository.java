package com.nexxserve.cavgomqt.repository;

import com.nexxserve.cavgomqt.entity.DriverStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DriverStateRepository extends JpaRepository<DriverStateEntity, Long> {
}
