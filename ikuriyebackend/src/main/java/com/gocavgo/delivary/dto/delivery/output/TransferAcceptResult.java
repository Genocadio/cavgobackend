package com.gocavgo.delivary.dto.delivery.output;

import com.gocavgo.delivary.dto.transfer.output.TransferResponse;

import java.util.List;

public record TransferAcceptResult(
        TransferResponse transfer,
        List<AcceptOfferResponse> acceptedPackages
) {
}
