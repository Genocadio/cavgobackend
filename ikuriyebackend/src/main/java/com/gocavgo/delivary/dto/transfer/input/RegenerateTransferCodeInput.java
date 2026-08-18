package com.gocavgo.delivary.dto.transfer.input;

import java.util.UUID;

public record RegenerateTransferCodeInput(
        UUID transferId
) {
}
