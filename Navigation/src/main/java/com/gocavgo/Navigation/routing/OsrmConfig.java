package com.gocavgo.Navigation.routing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "osrm")
@Data
public class OsrmConfig {
    private String url = "http://localhost:5000";
    private Timeout timeout = new Timeout();
    
    @Data
    public static class Timeout {
        private Duration seconds = Duration.ofSeconds(10);
    }
}

