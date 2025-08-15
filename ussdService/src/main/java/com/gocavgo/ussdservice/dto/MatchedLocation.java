package com.gocavgo.ussdservice.dto;

import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@Data
@RequiredArgsConstructor
@Setter
@Getter
public class MatchedLocation {
    private final String locationId;
    private final String name;
    private final boolean isWaypoint;
}
