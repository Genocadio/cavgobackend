package com.nexxserve.cavgomqt;

import com.nexxserve.cavgomqt.service.MqttService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.integration.annotation.IntegrationComponentScan;

@SpringBootApplication
@IntegrationComponentScan
public class CavgomqtApplication implements CommandLineRunner {

    @Autowired
    private MqttService mqttService;

    public static void main(String[] args) {
        SpringApplication.run(CavgomqtApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("=== MQTT Spring Integration Demo ===");
        System.out.println("Application started. MQTT subscriber is listening...");

        // Wait a bit for connection to establish
        Thread.sleep(2000);

        // Send a test message
        mqttService.pingAllCars();
    }

}
