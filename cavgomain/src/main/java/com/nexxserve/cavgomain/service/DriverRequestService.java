package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.dto.request.DriverRequestCreateDto;
import com.nexxserve.cavgomain.dto.response.DriverRequestResponseDto;
import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.entity.DriverRequest;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.enums.DriverRequestStatus;
import com.nexxserve.cavgomain.repository.CompanyRepository;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
import com.nexxserve.cavgomain.repository.DriverRequestRepository;
import com.nexxserve.cavgomain.security.NexxauthClient;
import com.nexxserve.cavgomain.security.NexxauthRoles;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Manages driver requests — normal users request to become drivers for a
 * company, and fleet managers approve or reject those requests.
 *
 * <p>On approval, the user's Nexxauth role is updated to include "driver"
 * and a local CompanyUser row is created/updated with DRIVER role.
 */
@Service
@RequiredArgsConstructor
public class DriverRequestService {

    private static final Logger log = LoggerFactory.getLogger(DriverRequestService.class);

    private final DriverRequestRepository driverRequestRepository;
    private final CompanyRepository companyRepository;
    private final CompanyUserRepository companyUserRepository;
    private final NexxauthClient nexxauthClient;

    /**
     * Creates a driver request for the authenticated user.
     *
     * @param companyCode the company the user wants to join
     * @param nexxauthUserId the Nexxauth user id from the JWT
     * @param firstName user's first name (from Nexxauth profile)
     * @param lastName user's last name
     * @param email user's email
     * @param phone user's phone
     * @return the created driver request
     */
    @Transactional
    public DriverRequestResponseDto createRequest(
            String companyCode,
            Long nexxauthUserId,
            String firstName,
            String lastName,
            String email,
            String phone
    ) {
        // Validate company exists
        Company company = companyRepository.findByCompanyCode(companyCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Company not found with code: " + companyCode));

        // Check if user already has a pending request for this company
        var existingPending = driverRequestRepository
                .findByNexxauthUserIdAndStatus(nexxauthUserId, DriverRequestStatus.PENDING);
        if (existingPending.isPresent()) {
            throw new IllegalArgumentException(
                    "You already have a pending driver request. Please wait for approval.");
        }

        // Check if user is already a driver for this company
        var existingCompanyUser = companyUserRepository.findById(nexxauthUserId);
        if (existingCompanyUser.isPresent() && existingCompanyUser.get().getCompany() != null
                && existingCompanyUser.get().getCompany().getId().equals(company.getId())
                && existingCompanyUser.get().getRole() == CompanyUserRole.DRIVER) {
            throw new IllegalArgumentException(
                    "You are already a driver for this company.");
        }

        DriverRequest request = new DriverRequest();
        request.setNexxauthUserId(nexxauthUserId);
        request.setFirstName(firstName != null ? firstName : "");
        request.setLastName(lastName);
        request.setEmail(email);
        request.setPhone(phone);
        request.setCompanyCode(companyCode);
        request.setCompany(company);
        request.setStatus(DriverRequestStatus.PENDING);

        DriverRequest saved = driverRequestRepository.save(request);
        log.info("Driver request created: userId={}, companyCode={}, requestId={}",
                nexxauthUserId, companyCode, saved.getId());
        return DriverRequestResponseDto.fromEntity(saved);
    }

    /**
     * Returns the authenticated user's most recent driver request, or null
     * if they have never requested. Used by ikuriye to show the current
     * status (pending/approved/rejected) and prevent duplicate requests.
     */
    @Transactional(readOnly = true)
    public DriverRequestResponseDto getMyLatestRequest(Long nexxauthUserId) {
        var request = driverRequestRepository.findByNexxauthUserId(nexxauthUserId);
        return request.map(DriverRequestResponseDto::fromEntity).orElse(null);
    }

    /**
     * Lists pending driver requests for a company (fleet manager view).
     */
    @Transactional(readOnly = true)
    public List<DriverRequestResponseDto> getPendingRequestsByCompany(Long companyId) {
        return driverRequestRepository.findByCompanyIdAndStatus(companyId, DriverRequestStatus.PENDING)
                .stream()
                .map(DriverRequestResponseDto::fromEntity)
                .toList();
    }

    /**
     * Lists all driver requests for a company (all statuses).
     */
    @Transactional(readOnly = true)
    public List<DriverRequestResponseDto> getAllRequestsByCompany(Long companyId) {
        return driverRequestRepository.findByCompanyId(companyId)
                .stream()
                .map(DriverRequestResponseDto::fromEntity)
                .toList();
    }

    /**
     * Gets a driver request by ID.
     */
    @Transactional(readOnly = true)
    public DriverRequestResponseDto getRequestById(Long requestId) {
        DriverRequest request = driverRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Driver request not found with id: " + requestId));
        return DriverRequestResponseDto.fromEntity(request);
    }

    /**
     * Approves a driver request:
     * 1. Updates the request status to APPROVED
     * 2. Adds the "driver" role to the user in Nexxauth (adds to existing roles)
     * 3. Creates/updates the local CompanyUser with DRIVER role
     */
    @Transactional
    public DriverRequestResponseDto approveRequest(Long requestId) {
        DriverRequest request = driverRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Driver request not found with id: " + requestId));

        if (request.getStatus() != DriverRequestStatus.PENDING) {
            throw new IllegalArgumentException(
                    "Request is not pending — current status: " + request.getStatus());
        }

        // 1. Update request status
        request.setStatus(DriverRequestStatus.APPROVED);
        driverRequestRepository.save(request);

        // 2. Update Nexxauth roles — add "driver" to existing roles
        try {
            var nexxauthUser = nexxauthClient.getUser(request.getNexxauthUserId());
            var currentRoles = nexxauthUser.roles();
            if (!currentRoles.contains("driver")) {
                var updatedRoles = new java.util.ArrayList<>(currentRoles);
                updatedRoles.add("driver");
                nexxauthClient.updateUserRoles(request.getNexxauthUserId(), updatedRoles);
                log.info("Added driver role in Nexxauth for userId={}", request.getNexxauthUserId());
            }
        } catch (Exception e) {
            log.error("Failed to update Nexxauth roles for userId={}: {}",
                    request.getNexxauthUserId(), e.getMessage());
            // Don't fail the whole approval — roles can be manually synced later
        }

        // 3. Create/update local CompanyUser with DRIVER role
        try {
            var existingUser = companyUserRepository.findById(request.getNexxauthUserId());
            if (existingUser.isPresent()) {
                var user = existingUser.get();
                user.setCompany(request.getCompany());
                user.setRole(CompanyUserRole.DRIVER);
                companyUserRepository.save(user);
                log.info("Updated CompanyUser to DRIVER for userId={}", request.getNexxauthUserId());
            } else {
                var newUser = new CompanyUser();
                newUser.setId(request.getNexxauthUserId());
                newUser.setFirstName(request.getFirstName());
                newUser.setLastName(request.getLastName() != null ? request.getLastName() : "");
                newUser.setEmail(request.getEmail());
                newUser.setPhone(request.getPhone());
                newUser.setCompany(request.getCompany());
                newUser.setRole(CompanyUserRole.DRIVER);
                newUser.setStatus(com.nexxserve.cavgomain.enums.UserStatus.ACTIVE);
                companyUserRepository.save(newUser);
                log.info("Created CompanyUser as DRIVER for userId={}", request.getNexxauthUserId());
            }
        } catch (Exception e) {
            log.error("Failed to create/update CompanyUser for userId={}: {}",
                    request.getNexxauthUserId(), e.getMessage());
        }

        log.info("Driver request approved: requestId={}, userId={}",
                requestId, request.getNexxauthUserId());
        return DriverRequestResponseDto.fromEntity(request);
    }

    /**
     * Rejects a driver request.
     */
    @Transactional
    public DriverRequestResponseDto rejectRequest(Long requestId, String reason) {
        DriverRequest request = driverRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Driver request not found with id: " + requestId));

        if (request.getStatus() != DriverRequestStatus.PENDING) {
            throw new IllegalArgumentException(
                    "Request is not pending — current status: " + request.getStatus());
        }

        request.setStatus(DriverRequestStatus.REJECTED);
        request.setRejectionReason(reason);
        driverRequestRepository.save(request);

        log.info("Driver request rejected: requestId={}, reason={}", requestId, reason);
        return DriverRequestResponseDto.fromEntity(request);
    }
}
