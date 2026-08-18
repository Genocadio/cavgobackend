package com.gocavgo.delivary.dto.transfer.input;

import com.gocavgo.delivary.enums.transfer.TransferAcceptorType;
import com.gocavgo.delivary.enums.transfer.TransferRuleType;

import java.util.UUID;

public record UpdateTransferInput(
        UUID transferId,
        TransferRuleType ruleType,
        TransferAcceptorType acceptorType
) {
}
