package com.gocavgo.delivary.dto.transfer.input;

import java.util.List;
import java.util.UUID;

public record AddPackagesToTransferInput(
        UUID transferId,
        List<UUID> packageIds
) {
}
