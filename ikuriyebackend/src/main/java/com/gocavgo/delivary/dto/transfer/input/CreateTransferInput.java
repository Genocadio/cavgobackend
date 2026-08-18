package com.gocavgo.delivary.dto.transfer.input;

import com.gocavgo.delivary.enums.transfer.TransferAcceptorType;
import com.gocavgo.delivary.enums.transfer.TransferRuleType;

import java.util.List;
import java.util.UUID;

public record CreateTransferInput(
        List<UUID> packageIds,
        TransferRuleType ruleType,
        TransferAcceptorType acceptorType,
        UUID matchCompanyId,
        Long matchUserId
) {
}
