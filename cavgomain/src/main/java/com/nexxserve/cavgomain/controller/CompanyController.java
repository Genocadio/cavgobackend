package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.request.CompanyRequestDto;
import com.nexxserve.cavgomain.dto.response.CompanyResponseDto;
import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.enums.CompanyStatus;
import com.nexxserve.cavgomain.service.CompanyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/main/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    @PostMapping
    public CompanyResponseDto createCompany(@RequestBody CompanyRequestDto company) {
        return companyService.createCompany(company);
    }

    @PutMapping("/{id}")
    public CompanyResponseDto updateCompany(@PathVariable Long id, @RequestBody CompanyRequestDto company) {
        return companyService.updateCompany(id, company);
    }

    @GetMapping("/{id}")
    public CompanyResponseDto getCompany(@PathVariable Long id) {
        return companyService.findById(id);
    }

    @GetMapping
    public List<CompanyResponseDto> getAllCompanies() {
        return companyService.findAll();
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
    public void deleteCompany(@PathVariable Long id) {
        companyService.deleteCompany(id);
    }
}