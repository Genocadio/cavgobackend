package com.gocavgo.delivary.dto.delivery.output;

import com.gocavgo.delivary.dto.transfer.output.TransferResponse;

public record PackageCreationResponse(
        PackageResponse deliveryPackage,
        TransferResponse transfer
) {
}
