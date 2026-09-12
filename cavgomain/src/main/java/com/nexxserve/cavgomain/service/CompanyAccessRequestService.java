package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.dto.response.CompanyAccessRequestResponseDto;
import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.entity.CompanyAccessRequest;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.enums.CompanyAccessRequestStatus;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.enums.DriverRequestStatus;
import com.nexxserve.cavgomain.enums.UserStatus;
import com.nexxserve.cavgomain.repository.CompanyAccessRequestRepository;
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
 * Manages company access & fleet-manager role requests. A user requests the
 * FLEET_MANAGER role and company access by entering the company code; another
 * fleet manager of that company must approve the request before the user is
 * assigned to the company AND granted the FLEET_MANAGER role in Nexxauth —
 * a user can never self-assign or self-approve. Every approval records who
 * approved (approvedBy).
 */
@Service
@RequiredArgsConstructor
public class CompanyAccessRequestService {

    private static final Logger log = LoggerFactory.getLogger(CompanyAccessRequestService.class);

    /** The role requested through the fleetman workflow. */
    private static final CompanyUserRole REQUESTED_ROLE = CompanyUserRole.FLEET_MANAGER;

    private final CompanyAccessRequestRepository requestRepository;
    private final CompanyRepository companyRepository;
    private final CompanyUserRepository companyUserRepository;
    private final DriverRequestRepository driverRequestRepository;
    private final AggregatorSyncService aggregatorSyncService;
    private final NexxauthClient nexxauthClient;

    /**
     * Creates a pending company access / fleet-manager role request for the
     * authenticated user.
     *
     * @param companyCode    the company the user wants access to
     * @param nexxauthUserId the Nexxauth user id from the JWT
     * @param firstName      user's first name (from the Nexxauth profile)
     * @param lastName       user's last name
     * @param email          user's email
     * @param phone          user's phone
     */
    @Transactional
    public CompanyAccessRequestResponseDto createRequest(
            String companyCode,
            Long nexxauthUserId,
            String firstName,
            String lastName,
            String email,
            String phone
    ) {
        Company company = companyRepository.findByCompanyCode(companyCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Company not found with code: " + companyCode));

        if (requestRepository.existsByNexxauthUserIdAndStatus(nexxauthUserId, CompanyAccessRequestStatus.PENDING)) {
            throw new IllegalArgumentException(
                    "You already have a pending company access request. Please wait for a fleet manager to approve it.");
        }

        // A user may only have one request in flight across both apps — block a
        // company access request while a driver request is still pending.
        if (driverRequestRepository
                .findByNexxauthUserIdAndStatus(nexxauthUserId, DriverRequestStatus.PENDING).isPresent()) {
            throw new IllegalArgumentException(
                    "You already have a pending driver request in the driver app. Please wait for it to be approved or rejected before requesting company access.");
        }

        var existingUser = companyUserRepository.findById(nexxauthUserId).orElse(null);
        if (existingUser != null && existingUser.getCompany() != null
                && existingUser.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException(
                    "You already have access to this company.");
        }

        CompanyAccessRequest request = new CompanyAccessRequest();
        request.setNexxauthUserId(nexxauthUserId);
        request.setFirstName(firstName != null ? firstName : "");
        request.setLastName(lastName);
        request.setEmail(email);
        request.setPhone(phone);
        request.setRole(REQUESTED_ROLE);
        request.setCompanyCode(companyCode);
        request.setCompany(company);
        request.setStatus(CompanyAccessRequestStatus.PENDING);

        CompanyAccessRequest saved = requestRepository.save(request);
        log.info("Company access request created: userId={}, companyCode={}, requestId={}",
                nexxauthUserId, companyCode, saved.getId());
        return CompanyAccessRequestResponseDto.fromEntity(saved);
    }

    /**
     * Returns the authenticated user's most recent company access request, or
     * null if they have never requested. Used by the app to show the current
     * status (pending/approved/rejected) while awaiting approval.
     */
    @Transactional(readOnly = true)
    public CompanyAccessRequestResponseDto getMyLatestRequest(Long nexxauthUserId) {
        var request = requestRepository.findTopByNexxauthUserIdOrderByCreatedAtDesc(nexxauthUserId);
        return request.map(CompanyAccessRequestResponseDto::fromEntity).orElse(null);
    }

    /**
     * Lists all company access requests for a company (fleet manager view).
     */
    @Transactional(readOnly = true)
    public List<CompanyAccessRequestResponseDto> getAllRequestsByCompany(Long companyId) {
        return requestRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .map(CompanyAccessRequestResponseDto::fromEntity)
                .toList();
    }

    /**
     * Lists pending company access requests for a company.
     */
    @Transactional(readOnly = true)
    public List<CompanyAccessRequestResponseDto> getPendingRequestsByCompany(Long companyId) {
        return requestRepository.findByCompanyIdAndStatus(companyId, CompanyAccessRequestStatus.PENDING).stream()
                .map(CompanyAccessRequestResponseDto::fromEntity)
                .toList();
    }

    /**
     * Approves a pending company access / fleet-manager role request. The
     * approving user must be a different user than the requester (no
     * self-approval). On approval:
     * <ol>
     *   <li>the "fleet_manager" role is added to the user in Nexxauth (if not already present)</li>
     *   <li>the user is assigned to the company with their effective role</li>
     *   <li>the approval is recorded (who approved + when)</li>
     * </ol>
     *
     * @param requestId     the request id
     * @param approverUserId the Nexxauth user id of the fleet manager approving
     */
    @Transactional
    public CompanyAccessRequestResponseDto approveRequest(Long requestId, Long approverUserId) {
        CompanyAccessRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Company access request not found with id: " + requestId));

        if (request.getStatus() != CompanyAccessRequestStatus.PENDING) {
            throw new IllegalArgumentException(
                    "Request is not pending — current status: " + request.getStatus());
        }
        if (approverUserId != null && approverUserId.equals(request.getNexxauthUserId())) {
            throw new IllegalArgumentException(
                    "You cannot approve your own company access request.");
        }

        // 1. Assign the requested role in Nexxauth (replaces existing roles such as "customer").
        CompanyUserRole effectiveRole = request.getRole() != null
                ? request.getRole() : REQUESTED_ROLE;
        String roleNexxauthName = NexxauthRoles.toNexxauthName(effectiveRole);
        if (roleNexxauthName == null) {
            roleNexxauthName = "fleet_manager";
        }
        try {
            nexxauthClient.updateUserRoles(request.getNexxauthUserId(), List.of(roleNexxauthName));
            log.info("Assigned {} role in Nexxauth for userId={} (request {})",
                    roleNexxauthName, request.getNexxauthUserId(), requestId);
            request.setRole(effectiveRole);
        } catch (Exception e) {
            log.error("Failed to assign {} role in Nexxauth for userId={} (request {}): {}",
                    roleNexxauthName, request.getNexxauthUserId(), requestId, e.getMessage());
        }

        // 2. Assign the requesting user to the company with their effective role.
        var existingUser = companyUserRepository.findById(request.getNexxauthUserId());
        if (existingUser.isPresent()) {
            var user = existingUser.get();
            user.setCompany(request.getCompany());
            user.setRole(effectiveRole);
            companyUserRepository.save(user);
            log.info("Assigned userId={} to companyId={} (request {})",
                    request.getNexxauthUserId(), request.getCompany().getId(), requestId);
        } else {
            var newUser = new CompanyUser();
            newUser.setId(request.getNexxauthUserId());
            newUser.setFirstName(request.getFirstName());
            newUser.setLastName(request.getLastName() != null ? request.getLastName() : "");
            newUser.setEmail(request.getEmail());
            newUser.setPhone(request.getPhone());
            newUser.setCompany(request.getCompany());
            newUser.setRole(effectiveRole);
            newUser.setStatus(UserStatus.ACTIVE);
            companyUserRepository.save(newUser);
            log.info("Created CompanyUser for userId={} in companyId={} (request {})",
                    request.getNexxauthUserId(), request.getCompany().getId(), requestId);
        }

        request.setStatus(CompanyAccessRequestStatus.APPROVED);
        request.setApprovedBy(approverUserId);
        request.setApprovedAt(LocalDateTime.now());
        CompanyAccessRequest saved = requestRepository.save(request);

        try {
            aggregatorSyncService.syncCompanyDataImmediately(request.getCompany().getId());
        } catch (Exception e) {
            log.warn("Error triggering aggregator sync after company access approval: {}", e.getMessage());
        }

        log.info("Company access request {} approved by userId={}",
                requestId, approverUserId);
        return CompanyAccessRequestResponseDto.fromEntity(saved);
    }

    /**
     * Rejects a pending company access request.
     *
     * @param requestId       the request id
     * @param reason          optional rejection reason
     * @param rejectedByUserId the Nexxauth user id of the staff member rejecting
     */
    @Transactional
    public CompanyAccessRequestResponseDto rejectRequest(Long requestId, String reason, Long rejectedByUserId) {
        CompanyAccessRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Company access request not found with id: " + requestId));

        if (request.getStatus() != CompanyAccessRequestStatus.PENDING) {
            throw new IllegalArgumentException(
                    "Request is not pending — current status: " + request.getStatus());
        }
        if (rejectedByUserId != null && rejectedByUserId.equals(request.getNexxauthUserId())) {
            throw new IllegalArgumentException(
                    "You cannot reject your own company access request.");
        }

        request.setStatus(CompanyAccessRequestStatus.REJECTED);
        request.setRejectionReason(reason);
        request.setRejectedAt(LocalDateTime.now());
        request.setRejectedBy(rejectedByUserId);
        CompanyAccessRequest saved = requestRepository.save(request);

        log.info("Company access request {} rejected by userId={}: reason={}",
                requestId, rejectedByUserId, reason);
        return CompanyAccessRequestResponseDto.fromEntity(saved);
    }
}