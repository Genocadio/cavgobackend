package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.request.CompanyUserRequestDto;
import com.nexxserve.cavgomain.dto.response.CompanyUserResponseDto;
import com.nexxserve.cavgomain.service.CompanyUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/main/staff")
@RequiredArgsConstructor
public class CompanyUserController {

    private final CompanyUserService companyUserService;

    @PostMapping
    public CompanyUserResponseDto createCompanyUser(@Valid @RequestBody CompanyUserRequestDto user) {
        return companyUserService.createCompanyUser(user);
    }

    @PutMapping("/{id}")
    public CompanyUserResponseDto updateCompanyUser(@PathVariable Long id, @Valid @RequestBody CompanyUserRequestDto user) {
        return companyUserService.updateCompanyUser(id, user);
    }

    /**
     * Assigns a company to a staff user. A user cannot assign themselves to a
     * company — company access is granted by a fleet manager through the
     * company access request approval flow.
     */
    @PutMapping("/{id}/company/{companyId}")
    public ResponseEntity<CompanyUserResponseDto> assignCompanyToUser(
            @PathVariable Long id, @PathVariable Long companyId) {
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        var currentUserId = (Long) request.getAttribute("nexxauthUserId");
        if (currentUserId != null && currentUserId.equals(id)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(companyUserService.assignCompanyToUser(id, companyId));
    }



    @GetMapping("/{id}")
    public CompanyUserResponseDto getCompanyUser(@PathVariable Long id) {
        return companyUserService.findById(id);
    }

    @GetMapping("/company/{companyId}")
    public List<CompanyUserResponseDto> getByCompanyId(
            @PathVariable Long companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timeLimit) {
        return companyUserService.findByCompanyId(companyId, timeLimit);
    }

    @GetMapping("/company/{companyId}/drivers")
    public Page<CompanyUserResponseDto> getDriversByCompany(
            @PathVariable Long companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timeLimit,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return companyUserService.findDriversByCompanyPaged(companyId, timeLimit, query, page, size);
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