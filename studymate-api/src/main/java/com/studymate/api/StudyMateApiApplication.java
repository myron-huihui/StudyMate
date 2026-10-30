package com.studymate.api;

import com.studymate.api.config.SecurityConfig;
import com.studymate.api.config.SwaggerConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * StudyMate API 主应用
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.studymate")
public class StudyMateApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(StudyMateApiApplication.class, args);
    }
}
