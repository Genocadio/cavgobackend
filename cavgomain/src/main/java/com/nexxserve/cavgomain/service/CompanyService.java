package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.dto.request.CompanyRequestDto;
import com.nexxserve.cavgomain.dto.response.CompanyResponseDto;
import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.enums.CompanyStatus;
import com.nexxserve.cavgomain.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class CompanyService {

    private final CompanyRepository companyRepository;

    public CompanyResponseDto createCompany(CompanyRequestDto company) {
        if (companyRepository.existsByCompanyName(company.getCompanyName())) {
            throw new IllegalArgumentException("Company code already exists");
        }
        Company newCompany = company.toEntity();
        newCompany.setCompanyCode(generateCompanyCode(newCompany.getCompanyName()));
        return CompanyResponseDto.fromEntity(companyRepository.save(newCompany));
    }

    private String generateCompanyCode(String companyName) {
        String name = companyName.replaceAll("[^A-Za-z]", "").toUpperCase();
        StringBuilder code = new StringBuilder();
        for (char c : name.toCharArray()) {
            if ("AEIOU".indexOf(c) == -1) {
                code.append(c);
                if (code.length() == 2) break;
            }
        }
        while (code.length() < 2) code.append('X');
        String digits;
        do {
            digits = String.format("%03d", (int)(Math.random() * 1000));
        } while (companyRepository.existsByCompanyCode(code + digits));
        return code + digits;
    }

    public CompanyResponseDto updateCompany(Long id, CompanyRequestDto company) {
        Company existingCompany = companyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Company not found with id: " + id));
        existingCompany.setCompanyName(company.getCompanyName());
        existingCompany.setEmail(company.getEmail());
        existingCompany.setPhone(company.getPhone());
        existingCompany.setAddress(company.getAddress());
        existingCompany.setCity(company.getCity());
        existingCompany.setStatus(company.getStatus());
        return CompanyResponseDto.fromEntity(companyRepository.save(existingCompany));
    }

    @Transactional(readOnly = true)
    public CompanyResponseDto findById(Long id) {
        Company company = companyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Company not found with id: " + id));
        return CompanyResponseDto.fromEntity(company);
    }

    @Transactional(readOnly = true)
    public List<CompanyResponseDto> findAll() {
        return companyRepository.findAll().stream()
                .map(CompanyResponseDto::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CompanyResponseDto> findByStatus(CompanyStatus status) {
        return companyRepository.findByStatus(status).stream()
                .map(CompanyResponseDto::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CompanyResponseDto> searchByName(String name) {
        return companyRepository.findByCompanyNameContainingIgnoreCase(name).stream()
                .map(CompanyResponseDto::fromEntity)
                .toList();
    }

    public void deleteCompany(Long id) {
        Company company = companyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Company not found with id: " + id));
        company.setStatus(CompanyStatus.INACTIVE);
        companyRepository.save(company);
    }
}
