package com.gocavgo.delivary.dto.delivery.output;

import java.util.List;

public record BulkDriverAssignmentResult(
        List<PackageResponse> packages,
        List<DriverAssignmentResponse> assignments
) {
}
