package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.request.CompanyRequestDto;
import com.nexxserve.cavgomain.dto.response.CompanyResponseDto;
import com.nexxserve.cavgomain.enums.CompanyStatus;
import com.nexxserve.cavgomain.service.CompanyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/main/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    /** Company creation is an application-superuser operation. */
    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public CompanyResponseDto createCompany(@Valid @RequestBody CompanyRequestDto company) {
        return companyService.createCompany(company);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public CompanyResponseDto updateCompany(@PathVariable Long id, @Valid @RequestBody CompanyRequestDto company) {
        return companyService.updateCompany(id, company);
    }

    @GetMapping("/{id}")
    public CompanyResponseDto getCompany(@PathVariable Long id) {
        return companyService.findById(id);
    }

    @GetMapping("/by-code/{code}")
    public CompanyResponseDto getCompanyByCode(@PathVariable String code) {
        return companyService.findByCompanyCode(code);
    }

    @GetMapping
    public List<CompanyResponseDto> getAllCompanies(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timeLimit) {
        return companyService.findAll(timeLimit);
    }

    @GetMapping("/status/{status}")
    public List<CompanyResponseDto> getByStatus(@PathVariable CompanyStatus status) {
        return companyService.findByStatus(status);
    }

    @GetMapping("/search")
    public List<CompanyResponseDto> searchByName(@RequestParam String name) {
        return companyService.searchByName(name);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public void deleteCompany(@PathVariable Long id) {
        companyService.deleteCompany(id);
    }
}