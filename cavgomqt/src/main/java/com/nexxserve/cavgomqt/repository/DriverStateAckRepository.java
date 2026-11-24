package com.nexxserve.cavgomqt.repository;

import com.nexxserve.cavgomqt.entity.DriverStateAckEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DriverStateAckRepository extends JpaRepository<DriverStateAckEntity, String> {

    List<DriverStateAckEntity> findByDriverId(Long driverId);

    long countByDriverIdAndLastAckedRevisionLessThan(Long driverId, long revision);
}
