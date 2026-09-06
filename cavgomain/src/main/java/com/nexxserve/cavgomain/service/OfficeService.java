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
        Company company = companyRepository.findByCompanyCode(request.getCompanyCode())
                .orElseThrow(() -> new IllegalArgumentException("Company not found with code: " + request.getCompanyCode()));

        Office office = request.toEntity(company);
        // Generate a company code for the office (JOINED inheritance — office is also a Company)
        office.setCompanyCode(generateOfficeCode(request.getName(), company.getCompanyCode()));
        Office saved = officeRepository.save(office);
        return OfficeResponseDto.fromEntity(saved);
    }

    public OfficeResponseDto updateOffice(Long id, OfficeRequestDto request) {
        Office existing = officeRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Office not found with id: " + id));

        if (request.getName() != null) existing.setCompanyName(request.getName());
        if (request.getEmail() != null) existing.setEmail(request.getEmail());
        if (request.getPhone() != null) existing.setPhone(request.getPhone());
        if (request.getAddress() != null) existing.setAddress(request.getAddress());
        if (request.getCity() != null) existing.setCity(request.getCity());
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
        // Office IS a Company (joined inheritance) — find offices assigned to workers of this company
        return companyUserRepository.findByCompanyId(companyId).stream()
                .map(CompanyUser::getOffice)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(OfficeResponseDto::fromEntity)
                .collect(Collectors.toList());
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
