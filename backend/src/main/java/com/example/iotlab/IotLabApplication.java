package com.example.iotlab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IotLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(IotLabApplication.class, args);
    }
}
