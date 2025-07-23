package com.nexxserve.eurekacavgo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@EnableEurekaServer
@SpringBootApplication
public class EurekacavgoApplication {

    public static void main(String[] args) {
        SpringApplication.run(EurekacavgoApplication.class, args);
    }

}
