package com.gocavgo.delivary.dto.delivery.input;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ConfirmDeliveryInput(
        String idempotencyKey,
        @NotNull UUID packageId,
        @NotBlank String deliveryCode,
        String proofPhotoUrl
) {
    public ConfirmDeliveryInput(@NotNull UUID packageId, @NotBlank String deliveryCode) {
        this(null, packageId, deliveryCode, null);
    }

    public ConfirmDeliveryInput(String idempotencyKey, @NotNull UUID packageId, @NotBlank String deliveryCode) {
        this(idempotencyKey, packageId, deliveryCode, null);
    }
}
