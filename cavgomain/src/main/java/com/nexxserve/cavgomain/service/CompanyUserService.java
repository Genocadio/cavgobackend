package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.dto.request.CompanyUserRequestDto;
import com.nexxserve.cavgomain.dto.response.CompanyUserResponseDto;
import com.nexxserve.cavgomain.dto.response.DriverVehicleResponseDto;
import com.nexxserve.cavgomain.dto.response.VehicleResponseDto;
import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.entity.Office;
import com.nexxserve.cavgomain.entity.VehicleAssignment;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.repository.CompanyRepository;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
import com.nexxserve.cavgomain.repository.OfficeRepository;
import com.nexxserve.cavgomain.repository.VehicleAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class CompanyUserService {

    private final CompanyUserRepository companyUserRepository;
    private final CompanyRepository companyRepository;
    private final OfficeRepository officeRepository;
    private final VehicleAssignmentRepository assignmentRepository;
    private final AggregatorSyncService aggregatorSyncService;

    public CompanyUserResponseDto createCompanyUser(CompanyUserRequestDto user) {
        Company company = companyRepository.findByCompanyCode(user.getCompanyCode())
                .orElseThrow(() -> new IllegalArgumentException("Company not found with Code: " + user.getCompanyCode()));
        if (user.getLicenseNumber() != null) {
            user.setRole(CompanyUserRole.DRIVER);
        }
        CompanyUser entity = user.toEntity(company);
        // Assign office if provided
        if (user.getOfficeId() != null) {
            Office office = officeRepository.findById(user.getOfficeId())
                    .orElseThrow(() -> new IllegalArgumentException("Office not found with id: " + user.getOfficeId()));
            entity.setOffice(office);
        }
        CompanyUser saved = companyUserRepository.save(entity);
        CompanyUserResponseDto dto = CompanyUserResponseDto.fromEntity(saved);
        
        // If user is a driver, populate vehicle information
        if (saved.getRole() == CompanyUserRole.DRIVER) {
            List<VehicleAssignment> activeAssignments = assignmentRepository.findActiveAssignmentsByDriver(saved.getId());
            if (!activeAssignments.isEmpty()) {
                VehicleAssignment activeAssignment = activeAssignments.get(0);
                dto.setVehicle(VehicleResponseDto.fromEntity(activeAssignment.getVehicle(), null));
            } else {
                dto.setVehicle(null);
            }
        }
        
        try {
            aggregatorSyncService.syncCompanyDataImmediately(saved.getCompany().getId());
        } catch (Exception e) {
            System.err.println("Error triggering aggregator sync after company user creation: " + e.getMessage());
        }
        
        return dto;
    }

    public CompanyUserResponseDto updateCompanyUser(Long id, CompanyUserRequestDto user) {
        CompanyUser existingUser = companyUserRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Company not found with id: " + id));
        existingUser.setFirstName(user.getFirstName());
        existingUser.setLastName(user.getLastName());
        existingUser.setEmail(user.getEmail());
        existingUser.setPhone(user.getPhone());
        existingUser.setRole(user.getRole());
        existingUser.setLicenseNumber(user.getLicenseNumber());
        existingUser.setLicenseExpiry(user.getLicenseExpiry());
        existingUser.setAddress(user.getAddress());
        existingUser.setStatus(user.getStatus());
        // Update office assignment if provided
        if (user.getOfficeId() != null) {
            Office office = officeRepository.findById(user.getOfficeId())
                    .orElseThrow(() -> new IllegalArgumentException("Office not found with id: " + user.getOfficeId()));
            existingUser.setOffice(office);
        } else if (user.getOfficeId() == null && existingUser.getOffice() != null) {
            // Explicit null clears the office assignment
            existingUser.setOffice(null);
        }
        
        CompanyUser saved = companyUserRepository.save(existingUser);
        CompanyUserResponseDto dto = CompanyUserResponseDto.fromEntity(saved);
        
        if (saved.getRole() == CompanyUserRole.DRIVER) {
            List<VehicleAssignment> activeAssignments = assignmentRepository.findActiveAssignmentsByDriver(saved.getId());
            if (!activeAssignments.isEmpty()) {
                VehicleAssignment activeAssignment = activeAssignments.get(0);
                dto.setVehicle(VehicleResponseDto.fromEntity(activeAssignment.getVehicle(), null));
            } else {
                dto.setVehicle(null);
            }
        }
        
        return dto;
    }

    @Transactional(readOnly = true)
    public CompanyUserResponseDto findById(Long id) {
        CompanyUser user = companyUserRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Company user not found with id: " + id));
        
        CompanyUserResponseDto dto = CompanyUserResponseDto.fromEntity(user);
        
        if (user.getRole() == CompanyUserRole.DRIVER) {
            List<VehicleAssignment> activeAssignments = assignmentRepository.findActiveAssignmentsByDriver(user.getId());
            if (!activeAssignments.isEmpty()) {
                VehicleAssignment activeAssignment = activeAssignments.get(0);
                dto.setVehicle(VehicleResponseDto.fromEntity(activeAssignment.getVehicle(), null));
            } else {
                dto.setVehicle(null);
            }
        }
        
        return dto;
    }

    public List<DriverVehicleResponseDto> getDrivers(Long companyId) {
        List<DriverVehicleResponseDto> drivers = companyUserRepository.findByCompanyIdAndRole(companyId, CompanyUserRole.DRIVER).stream()
                .map(DriverVehicleResponseDto::fromEntity).toList();
        if (drivers.isEmpty()) {
            throw new IllegalArgumentException("No drivers found for company with id: " + companyId);
        }
        return drivers;
    }

    public DriverVehicleResponseDto getDriver(Long id) {
        return companyUserRepository.findById(id)
                .filter(user -> user.getRole() == CompanyUserRole.DRIVER)
                .map(DriverVehicleResponseDto::fromEntity)
                .orElseThrow(() -> new IllegalArgumentException("Driver not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public List<CompanyUserResponseDto> findByCompanyId(Long companyId, LocalDateTime timeLimit) {
        List<CompanyUser> users;
        if (timeLimit != null) {
            users = companyUserRepository.findByCompanyIdAfterTime(companyId, timeLimit);
        } else {
            // Use a very old date to get all records
            users = companyUserRepository.findByCompanyIdAfterTime(companyId, LocalDateTime.of(1970, 1, 1, 0, 0));
        }
        return users.stream()
                .map(user -> {
                    CompanyUserResponseDto dto = CompanyUserResponseDto.fromEntity(user);
                    
                    // If user is a driver, populate vehicle information
                    if (user.getRole() == CompanyUserRole.DRIVER) {
                        List<VehicleAssignment> activeAssignments = assignmentRepository.findActiveAssignmentsByDriver(user.getId());
                        if (!activeAssignments.isEmpty()) {
                            VehicleAssignment activeAssignment = activeAssignments.get(0);
                            // Pass null as driver to prevent recursion
                            dto.setVehicle(VehicleResponseDto.fromEntity(activeAssignment.getVehicle(), null));
                        } else {
                            dto.setVehicle(null);
                        }
                    }
                    
                    return dto;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<CompanyUserResponseDto> findDriversByCompanyPaged(
            Long companyId,
            LocalDateTime timeLimit,
            String query,
            int page,
            int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        String normalizedQuery = normalizeQueryFilter(query);
        LocalDateTime normalizedTimeLimit = timeLimit == null
            ? LocalDateTime.of(1970, 1, 1, 0, 0)
            : timeLimit;

        return companyUserRepository.searchDriversByCompany(companyId, normalizedTimeLimit, normalizedQuery, pageable)
                .map(driver -> {
                    CompanyUserResponseDto dto = CompanyUserResponseDto.fromEntity(driver);

                    List<VehicleAssignment> activeAssignments = assignmentRepository.findActiveAssignmentsByDriver(driver.getId());
                    if (!activeAssignments.isEmpty()) {
                        VehicleAssignment activeAssignment = activeAssignments.get(0);
                        dto.setVehicle(VehicleResponseDto.fromEntity(activeAssignment.getVehicle(), null));
                    } else {
                        dto.setVehicle(null);
                    }

                    return dto;
                });
    }

    @Transactional(readOnly = true)
    public List<CompanyUserResponseDto> findUsersWithExpiredLicense() {
        return companyUserRepository.findUsersWithExpiredLicense().stream()
                .map(user -> {
                    CompanyUserResponseDto dto = CompanyUserResponseDto.fromEntity(user);
                    
                    // If user is a driver, populate vehicle information
                    if (user.getRole() == CompanyUserRole.DRIVER) {
                        List<VehicleAssignment> activeAssignments = assignmentRepository.findActiveAssignmentsByDriver(user.getId());
                        if (!activeAssignments.isEmpty()) {
                            VehicleAssignment activeAssignment = activeAssignments.get(0);
                            // Pass null as driver to prevent recursion
                            dto.setVehicle(VehicleResponseDto.fromEntity(activeAssignment.getVehicle(), null));
                        } else {
                            dto.setVehicle(null);
                        }
                    }
                    
                    return dto;
                })
                .toList();
    }

    public void deleteCompanyUser(Long id) {
        companyUserRepository.deleteById(id);
    }

    private String normalizeQueryFilter(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}