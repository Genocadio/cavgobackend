package com.gocavgo.ussdservice.contoller;


import com.gocavgo.ussdservice.dto.USSDRequest;
import com.gocavgo.ussdservice.service.USSDService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ussd")
@RequiredArgsConstructor
@Slf4j
public class USSDController {

    private final USSDService ussdService;

    @PostMapping(value = "/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> handleUSSDCallback(
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "serviceCode", required = false) String serviceCode,
            @RequestParam(name = "phoneNumber") String phoneNumber,
            @RequestParam(name = "text", defaultValue = "") String text) {

        log.info("USSD callback received - Phone: {}, Text: {}, SessionId: {}",
                phoneNumber, text, sessionId);

        USSDRequest request = new USSDRequest();
        request.setSessionId(sessionId);
        request.setServiceCode(serviceCode);
        request.setPhoneNumber(phoneNumber);
        request.setText(text);

        String response = ussdService.processUSSDRequest(request);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(response);
    }

    // Health check endpoint
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("USSD Service is running");
    }
}