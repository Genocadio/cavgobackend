package com.gocavgo.delivary.dto.delivery.output;

import java.util.List;

public record BulkOfficeClaimResult(
        List<PackageResponse> packages,
        List<OfficeClaimResponse> claims
) {
}
