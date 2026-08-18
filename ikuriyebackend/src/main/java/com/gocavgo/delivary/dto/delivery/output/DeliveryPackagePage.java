package com.gocavgo.delivary.dto.delivery.output;

import java.util.List;

public record DeliveryPackagePage(
        List<PackageResponse> items,
        int totalCount,
        int totalPages,
        int currentPage
) {
}
