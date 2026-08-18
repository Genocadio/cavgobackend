package com.gocavgo.delivary.dto.delivery.output;

public record DeliveryCodeResult(
        PackageResponse deliveryPackage,
        String deliveryCode
) {
}
