package com.gocavgo.delivary.dto.delivery.output;

import com.gocavgo.delivary.controller.graphql.NoticeResolver.NoticeResponse;

import java.util.List;

public record CatchupSyncResult(
        List<PackageResponse> packages,
        List<NoticeResponse> notices,
        String syncedAt
) {
}
