package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.request.CompanyAdminAccessRequestDto;
import com.nexxserve.cavgomain.service.CompanyAdminAccessRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * Stores company administrator requests separately from the fleet-manager
 * company access request workflow. Listing and approval are intentionally not
 * exposed here; that workflow will be added separately.
 */
@RestController
@RequestMapping("/main/company-admin-access-requests")
@RequiredArgsConstructor
public class CompanyAdminAccessRequestController {

    private final CompanyAdminAccessRequestService requestService;

    @PostMapping("/validate-company-code")
    public ResponseEntity<Void> validateCompanyCode(@Valid @RequestBody CompanyAdminAccessRequestDto request) {
        if (!requestService.isCompanyCodeValid(request.getCompanyCode())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping
    public ResponseEntity<?> createRequest(@Valid @RequestBody CompanyAdminAccessRequestDto request) {
        var servletRequest = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                .getRequest();
        var userId = (Long) servletRequest.getAttribute("nexxauthUserId");
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authenticated Nexxauth user is required"));
        }

        try {
            requestService.createRequest(request.getCompanyCode(), userId);
            return ResponseEntity.accepted().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
