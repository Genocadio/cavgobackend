package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.dto.request.CompanyUserRequestDto;
import com.nexxserve.cavgomain.dto.response.CompanyUserResponseDto;
import com.nexxserve.cavgomain.dto.response.DriverVehicleResponseDto;
import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.repository.CompanyRepository;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
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

    public CompanyUserResponseDto createCompanyUser(CompanyUserRequestDto user) {
        if (companyUserRepository.findByEmail(user.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email already exists");
        }
        Company company = companyRepository.findById(user.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found with id: " + user.getCompanyId()));
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        CompanyUser saved = companyUserRepository.save(user.toEntity(company));
        return CompanyUserResponseDto.fromEntity(saved);
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
        return CompanyUserResponseDto.fromEntity(companyUserRepository.save(existingUser));
    }

    @Transactional(readOnly = true)
    public CompanyUserResponseDto findById(Long id) {
        CompanyUser user = companyUserRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Company user not found with id: " + id));
        return CompanyUserResponseDto.fromEntity(user);
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
                .map(CompanyUserResponseDto::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CompanyUserResponseDto> findDriversByCompany(Long companyId) {
        return companyUserRepository.findByCompanyIdAndRole(companyId, CompanyUserRole.DRIVER).stream()
                .map(CompanyUserResponseDto::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CompanyUserResponseDto> findUsersWithExpiredLicense() {
        return companyUserRepository.findUsersWithExpiredLicense().stream()
                .map(CompanyUserResponseDto::fromEntity)
                .toList();
    }

    public void deleteCompanyUser(Long id) {
        companyUserRepository.deleteById(id);
    }
}