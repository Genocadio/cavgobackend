package com.gocavgo.delivary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DelivaryApplication {

	public static void main(String[] args) {
		SpringApplication.run(DelivaryApplication.class, args);
	}

}
