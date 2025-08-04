package com.gocavgo.ussdservice.dto;

import lombok.Data;

@Data
public class USSDRequest {
    private String sessionId;
    private String serviceCode;
    private String phoneNumber;
    private String text;
}