package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.dto.request.CompanyUserRequestDto;
import com.nexxserve.cavgomain.dto.response.CompanyUserResponseDto;
import com.nexxserve.cavgomain.dto.response.DriverVehicleResponseDto;
import com.nexxserve.cavgomain.dto.response.VehicleResponseDto;
import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.entity.VehicleAssignment;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.repository.CompanyRepository;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
import com.nexxserve.cavgomain.repository.UserRepository;
import com.nexxserve.cavgomain.repository.VehicleAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class CompanyUserService {

    private final CompanyUserRepository companyUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final CompanyRepository companyRepository;
    private final VehicleAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final AggregatorSyncService aggregatorSyncService;

    public CompanyUserResponseDto createCompanyUser(CompanyUserRequestDto user) {
        // Check across ALL user types (CompanyUser, ClientUser, etc.)
        if (userRepository.findByEmail(user.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email already exists");
        }
        if (userRepository.findByPhone(user.getPhone()).isPresent()) {
            throw new IllegalArgumentException("Phone already exists");
        }
        Company company = companyRepository.findByCompanyCode(user.getCompanyCode())
                .orElseThrow(() -> new IllegalArgumentException("Company not found with Code: " + user.getCompanyCode()));
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        if (user.getLicenseNumber() != null) {
            user.setRole(CompanyUserRole.DRIVER);
        }
        CompanyUser saved = companyUserRepository.save(user.toEntity(company));
        CompanyUserResponseDto dto = CompanyUserResponseDto.fromEntity(saved);
        
        // If user is a driver, populate vehicle information
        if (saved.getRole() == CompanyUserRole.DRIVER) {
            List<VehicleAssignment> activeAssignments = assignmentRepository.findActiveAssignmentsByDriver(saved.getId());
            if (!activeAssignments.isEmpty()) {
                VehicleAssignment activeAssignment = activeAssignments.get(0);
                // Pass null as driver to prevent recursion
                dto.setVehicle(VehicleResponseDto.fromEntity(activeAssignment.getVehicle(), null));
            } else {
                dto.setVehicle(null);
            }
        }
        
        // Trigger immediate aggregator sync for company user creation
        try {
            aggregatorSyncService.syncCompanyDataImmediately(saved.getCompany().getId());
        } catch (Exception e) {
            // Log error but don't fail the creation
            // Using System.err as fallback if logger not available
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
        
        CompanyUser saved = companyUserRepository.save(existingUser);
        CompanyUserResponseDto dto = CompanyUserResponseDto.fromEntity(saved);
        
        // If user is a driver, populate vehicle information
        if (saved.getRole() == CompanyUserRole.DRIVER) {
            List<VehicleAssignment> activeAssignments = assignmentRepository.findActiveAssignmentsByDriver(saved.getId());
            if (!activeAssignments.isEmpty()) {
                VehicleAssignment activeAssignment = activeAssignments.get(0);
                // Pass null as driver to prevent recursion
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
    public List<CompanyUserResponseDto> findByCompanyId(Long companyId) {
        return companyUserRepository.findByCompanyId(companyId).stream()
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
    public List<CompanyUserResponseDto> findDriversByCompany(Long companyId) {
        return companyUserRepository.findByCompanyIdAndRole(companyId, CompanyUserRole.DRIVER).stream()
                .map(driver -> {
                    CompanyUserResponseDto dto = CompanyUserResponseDto.fromEntity(driver);
                    
                    // Check if driver has an active vehicle assignment
                    List<VehicleAssignment> activeAssignments = assignmentRepository.findActiveAssignmentsByDriver(driver.getId());
                    if (!activeAssignments.isEmpty()) {
                        VehicleAssignment activeAssignment = activeAssignments.get(0);
                        // Pass null as driver to prevent recursion
                        dto.setVehicle(VehicleResponseDto.fromEntity(activeAssignment.getVehicle(), null));
                    } else {
                        dto.setVehicle(null);
                    }
                    
                    return dto;
                })
                .toList();
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

    @Transactional(readOnly = true)
    public List<CompanyUserResponseDto> searchDriversByCompanyAndName(Long companyId, String searchQuery) {
        return companyUserRepository.searchDriversByCompanyAndName(companyId, searchQuery).stream()
                .map(driver -> {
                    CompanyUserResponseDto dto = CompanyUserResponseDto.fromEntity(driver);
                    
                    // Check if driver has an active vehicle assignment
                    List<VehicleAssignment> activeAssignments = assignmentRepository.findActiveAssignmentsByDriver(driver.getId());
                    if (!activeAssignments.isEmpty()) {
                        VehicleAssignment activeAssignment = activeAssignments.get(0);
                        // Pass null as driver to prevent recursion
                        dto.setVehicle(VehicleResponseDto.fromEntity(activeAssignment.getVehicle(), null));
                    } else {
                        dto.setVehicle(null);
                    }
                    
                    return dto;
                })
                .toList();
    }

    public void deleteCompanyUser(Long id) {
        companyUserRepository.deleteById(id);
    }
}