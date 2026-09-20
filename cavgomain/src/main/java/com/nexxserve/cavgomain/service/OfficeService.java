package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.dto.request.OfficeRequestDto;
import com.nexxserve.cavgomain.dto.response.OfficeResponseDto;
import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.entity.Office;
import com.nexxserve.cavgomain.repository.CompanyRepository;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
import com.nexxserve.cavgomain.repository.OfficeRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class OfficeService {

    private final OfficeRepository officeRepository;
    private final CompanyRepository companyRepository;
    private final CompanyUserRepository companyUserRepository;

    public OfficeResponseDto createOffice(OfficeRequestDto request) {
        if (request.getLocationId() == null) {
            throw new IllegalArgumentException("locationId is required when creating an office (must be selected from cavgotrips locations)");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("name is required when creating an office");
        }

        Company company = null;
        if (request.getCompanyId() != null) {
            company = companyRepository.findById(request.getCompanyId()).orElse(null);
        }
        if (company == null && request.getCompanyCode() != null && !request.getCompanyCode().isBlank()) {
            company = companyRepository.findByCompanyCode(request.getCompanyCode().trim()).orElse(null);
        }
        if (company == null) {
            throw new IllegalArgumentException("Company not found. Either valid companyId or companyCode is required.");
        }

        Office office = request.toEntity(company);
        office.setParentCompany(company);
        // Generate a company code for the office (JOINED inheritance — office is also a Company)
        office.setCompanyCode(generateOfficeCode(request.getName(), company.getCompanyCode()));
        Office saved = officeRepository.save(office);
        return OfficeResponseDto.fromEntity(saved);
    }

    public OfficeResponseDto updateOffice(Long id, OfficeRequestDto request) {
        Office existing = officeRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Office not found with id: " + id));

        if (request.getName() != null && !request.getName().isBlank()) existing.setCompanyName(request.getName());
        if (request.getEmail() != null) existing.setEmail(request.getEmail());
        if (request.getPhone() != null) existing.setPhone(request.getPhone());
        if (request.getAddress() != null) existing.setAddress(request.getAddress());
        if (request.getCity() != null) existing.setCity(request.getCity());
        if (request.getLocationId() != null) existing.setLocationId(request.getLocationId());
        if (request.getLatitude() != null) existing.setLatitude(request.getLatitude());
        if (request.getLongitude() != null) existing.setLongitude(request.getLongitude());
        if (request.getGooglePlaceName() != null) existing.setGooglePlaceName(request.getGooglePlaceName());
        if (request.getCustomName() != null) existing.setCustomName(request.getCustomName());
        if (request.getPlaceId() != null) existing.setPlaceId(request.getPlaceId());
        if (request.getContactEmails() != null) existing.setContactEmails(new java.util.ArrayList<>(request.getContactEmails()));
        if (request.getContactPhones() != null) existing.setContactPhones(new java.util.ArrayList<>(request.getContactPhones()));

        Office saved = officeRepository.save(existing);
        return OfficeResponseDto.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public OfficeResponseDto findById(Long id) {
        Office office = officeRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Office not found with id: " + id));
        return OfficeResponseDto.fromEntity(office);
    }

    @Transactional(readOnly = true)
    public List<OfficeResponseDto> findByCompanyId(Long companyId) {
        List<Office> byParent = officeRepository.findByParentCompanyId(companyId);
        if (!byParent.isEmpty()) {
            return byParent.stream()
                    .map(OfficeResponseDto::fromEntity)
                    .collect(Collectors.toList());
        }
        // Fallback for legacy rows linked via company users
        return companyUserRepository.findByCompanyId(companyId).stream()
                .map(CompanyUser::getOffice)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(OfficeResponseDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<OfficeResponseDto> findByCompanyCode(String companyCode) {
        Company company = companyRepository.findByCompanyCode(companyCode)
                .orElseThrow(() -> new EntityNotFoundException("Company not found with code: " + companyCode));
        return findByCompanyId(company.getId());
    }

    @Transactional(readOnly = true)
    public List<OfficeResponseDto> findAll() {
        return officeRepository.findAll().stream()
                .map(OfficeResponseDto::fromEntity)
                .collect(Collectors.toList());
    }

    public void deleteOffice(Long id) {
        if (!officeRepository.existsById(id)) {
            throw new EntityNotFoundException("Office not found with id: " + id);
        }
        // Unassign users from this office before deleting
        companyUserRepository.findAll().stream()
                .filter(u -> u.getOffice() != null && u.getOffice().getId().equals(id))
                .forEach(u -> {
                    u.setOffice(null);
                    companyUserRepository.save(u);
                });
        officeRepository.deleteById(id);
    }

    private String generateOfficeCode(String officeName, String companyCode) {
        String prefix = companyCode.length() >= 2 ? companyCode.substring(0, 2) : "OF";
        String namePart = officeName.replaceAll("[^A-Za-z]", "").toUpperCase();
        StringBuilder code = new StringBuilder(prefix);
        for (char c : namePart.toCharArray()) {
            if ("AEIOU".indexOf(c) == -1) {
                code.append(c);
                if (code.length() >= 5) break;
            }
        }
        while (code.length() < 5) code.append('X');
        String digits = String.format("%03d", (int) (Math.random() * 1000));
        return code + digits;
    }
}
