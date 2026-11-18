package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.entity.CompanyAdminAccessRequest;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.enums.CompanyAdminAccessRequestStatus;
import com.nexxserve.cavgomain.enums.UserStatus;
import com.nexxserve.cavgomain.dto.response.CompanyAdminAccessRequestResponseDto;
import com.nexxserve.cavgomain.repository.CompanyAdminAccessRequestRepository;
import com.nexxserve.cavgomain.repository.CompanyRepository;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
import com.nexxserve.cavgomain.security.NexxauthClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional
public class CompanyAdminAccessRequestService {

    private final CompanyAdminAccessRequestRepository requestRepository;
    private final CompanyRepository companyRepository;
    private final CompanyUserRepository companyUserRepository;
    private final NexxauthClient nexxauthClient;

    @Transactional(readOnly = true)
    public boolean isCompanyCodeValid(String companyCode) {
        return companyCode != null
                && !companyCode.isBlank()
                && companyRepository.findByCompanyCode(normalize(companyCode)).isPresent();
    }

    public void createRequest(String companyCode, Long nexxauthUserId) {
        if (nexxauthUserId == null) {
            throw new IllegalArgumentException("Authenticated Nexxauth user is required");
        }

        String normalizedCode = normalize(companyCode);
        var company = companyRepository.findByCompanyCode(normalizedCode)
                .orElseThrow(() -> new IllegalArgumentException("Company code is invalid"));

        if (requestRepository.existsByNexxauthUserIdAndCompanyId(nexxauthUserId, company.getId())) {
            return;
        }

        var authUser = nexxauthClient.getUser(nexxauthUserId);
        var request = new CompanyAdminAccessRequest();
        request.setNexxauthUserId(nexxauthUserId);
        request.setFirstName(authUser.firstName());
        request.setLastName(authUser.lastName());
        request.setEmail(authUser.email());
        request.setPhone(authUser.phone());
        request.setCompanyCode(normalizedCode);
        request.setCompany(company);
        request.setStatus(CompanyAdminAccessRequestStatus.PENDING);
        requestRepository.save(request);
    }

    @Transactional(readOnly = true)
    public List<CompanyAdminAccessRequestResponseDto> getPendingRequests(Long companyId) {
        return requestRepository.findByCompanyIdAndStatusOrderByCreatedAtDesc(
                        companyId, CompanyAdminAccessRequestStatus.PENDING)
                .stream()
                .map(CompanyAdminAccessRequestResponseDto::fromEntity)
                .toList();
    }

    public CompanyAdminAccessRequestResponseDto approve(Long companyId, Long requestId, Long approverUserId) {
        CompanyAdminAccessRequest request = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Company admin request not found"));
        ensureRequestCompany(request, companyId);
        if (request.getStatus() == CompanyAdminAccessRequestStatus.APPROVED) {
            return CompanyAdminAccessRequestResponseDto.fromEntity(request);
        }
        if (request.getStatus() != CompanyAdminAccessRequestStatus.PENDING) {
            throw new IllegalArgumentException("Company admin request is not pending");
        }
        if (approverUserId == null || approverUserId.equals(request.getNexxauthUserId())) {
            throw new IllegalArgumentException("You cannot approve your own company admin request");
        }

        CompanyUser user = companyUserRepository.findById(request.getNexxauthUserId()).orElseGet(() -> {
            CompanyUser newUser = new CompanyUser();
            newUser.setId(request.getNexxauthUserId());
            return newUser;
        });
        if (user.getCompany() != null && !user.getCompany().getId().equals(companyId)) {
            throw new IllegalArgumentException("The requesting user already belongs to another company");
        }
        var identity = nexxauthClient.getUser(request.getNexxauthUserId());
        if (!identity.enabled()) {
            throw new IllegalArgumentException("The requesting Nexxauth account is disabled");
        }
        nexxauthClient.updateUserRoles(request.getNexxauthUserId(), List.of("admin"));

        user.setFirstName(identity.firstName() != null ? identity.firstName() : request.getFirstName());
        user.setLastName(identity.lastName() != null ? identity.lastName() : request.getLastName());
        user.setEmail(identity.email() != null ? identity.email() : request.getEmail());
        user.setPhone(identity.phone() != null ? identity.phone() : request.getPhone());
        user.setCompany(request.getCompany());
        user.setRole(com.nexxserve.cavgomain.enums.CompanyUserRole.ADMIN);
        user.setStatus(UserStatus.ACTIVE);
        companyUserRepository.save(user);

        request.setStatus(CompanyAdminAccessRequestStatus.APPROVED);
        request.setApprovedBy(approverUserId);
        request.setApprovedAt(LocalDateTime.now());
        return CompanyAdminAccessRequestResponseDto.fromEntity(requestRepository.save(request));
    }

    public CompanyAdminAccessRequestResponseDto reject(
            Long companyId, Long requestId, Long reviewerUserId, String reason) {
        CompanyAdminAccessRequest request = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Company admin request not found"));
        ensureRequestCompany(request, companyId);
        if (request.getStatus() == CompanyAdminAccessRequestStatus.REJECTED) {
            return CompanyAdminAccessRequestResponseDto.fromEntity(request);
        }
        if (request.getStatus() != CompanyAdminAccessRequestStatus.PENDING) {
            throw new IllegalArgumentException("Company admin request is not pending");
        }
        if (reviewerUserId == null || reviewerUserId.equals(request.getNexxauthUserId())) {
            throw new IllegalArgumentException("You cannot reject your own company admin request");
        }
        request.setStatus(CompanyAdminAccessRequestStatus.REJECTED);
        request.setRejectedBy(reviewerUserId);
        request.setRejectedAt(LocalDateTime.now());
        request.setRejectionReason(reason);
        return CompanyAdminAccessRequestResponseDto.fromEntity(requestRepository.save(request));
    }

    private void ensureRequestCompany(CompanyAdminAccessRequest request, Long companyId) {
        if (request.getCompany() == null || !request.getCompany().getId().equals(companyId)) {
            throw new IllegalArgumentException("Company admin request does not belong to this company");
        }
    }

    private String normalize(String companyCode) {
        return companyCode == null ? "" : companyCode.trim().toUpperCase(Locale.ROOT);
    }
}
