package com.gocavgo.delivary.dto.transfer.output;

import com.gocavgo.delivary.enums.transfer.TransferAcceptorType;
import com.gocavgo.delivary.enums.transfer.TransferRuleType;
import com.gocavgo.delivary.enums.transfer.TransferStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        Long creatorId,
        TransferRuleType ruleType,
        TransferAcceptorType acceptorType,
        UUID matchCompanyId,
        Long matchUserId,
        Long requestorId,
        TransferStatus status,
        String transferCode,
        List<TransferPackageResponse> packages,
        Instant createdAt,
        Instant updatedAt
) {
    public record TransferPackageResponse(
            UUID id,
            UUID transferId,
            UUID packageId,
            Long addedBy,
            Instant addedAt
    ) {
    }
}
