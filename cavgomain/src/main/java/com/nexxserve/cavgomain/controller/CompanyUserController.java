package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.request.CompanyUserRequestDto;
import com.nexxserve.cavgomain.dto.response.CompanyUserResponseDto;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.service.CompanyUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/main/staff")
@RequiredArgsConstructor
public class CompanyUserController {

    private final CompanyUserService companyUserService;

    @PostMapping
    public CompanyUserResponseDto createCompanyUser(@RequestBody CompanyUserRequestDto user) {
        return companyUserService.createCompanyUser(user);
    }

    @PutMapping("/{id}")
    public CompanyUserResponseDto updateCompanyUser(@PathVariable Long id, @RequestBody CompanyUserRequestDto user) {
        return companyUserService.updateCompanyUser(id, user);
    }

    @GetMapping("/{id}")
    public CompanyUserResponseDto getCompanyUser(@PathVariable Long id) {
        return companyUserService.findById(id);
    }

    @GetMapping("/company/{companyId}")
    public List<CompanyUserResponseDto> getByCompanyId(@PathVariable Long companyId) {
        return companyUserService.findByCompanyId(companyId);
    }

    @GetMapping("/company/{companyId}/drivers")
    public List<CompanyUserResponseDto> getDriversByCompany(@PathVariable Long companyId) {
        return companyUserService.findDriversByCompany(companyId);
    }

    @GetMapping("/expired-licenses")
    public List<CompanyUserResponseDto> getUsersWithExpiredLicense() {
        return companyUserService.findUsersWithExpiredLicense();
    }

    @DeleteMapping("/{id}")
    public void deleteCompanyUser(@PathVariable Long id) {
        companyUserService.deleteCompanyUser(id);
    }
}