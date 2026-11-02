package com.studymate.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * StudyMate Gateway 主应用
 */
@SpringBootApplication
@EnableDiscoveryClient
public class StudyMateGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(StudyMateGatewayApplication.class, args);
    }
}
