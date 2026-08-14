package com.careeros.agent;

import com.careeros.agent.config.CareerOsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(CareerOsProperties.class)
public class CareerOsAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(CareerOsAgentApplication.class, args);
    }
}
