package com.nexxserve.cavgomqt;

import com.nexxserve.cavgomqt.service.MqttService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.integration.annotation.IntegrationComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@IntegrationComponentScan
@EnableScheduling
public class CavgomqtApplication implements CommandLineRunner {

    @Autowired
    private MqttService mqttService;

    public static void main(String[] args) {
        SpringApplication.run(CavgomqtApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("🚀 CAVGOMQT APPLICATION STARTED");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("✅ MQTT subscriber is listening...");
        System.out.println("✅ RabbitMQ listeners are active (see logs above for details)");
        System.out.println("═══════════════════════════════════════════════════════════════");

        // Wait a bit for connections to establish
        Thread.sleep(2000);

        // Send a test message
        mqttService.pingAllCars();
    }

}
