package com.studymate.api.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 就绪探针只看主库与 Redis；ES/pgVector 属于可降级依赖，不参与就绪判断。
 */
@Configuration
public class HealthConfig {

    @Bean
    public HealthIndicator mysqlHealthIndicator(@Qualifier("mysqlDataSource") DataSource dataSource) {
        return () -> {
            try (Connection connection = dataSource.getConnection()) {
                return connection.isValid(2)
                        ? Health.up().withDetail("database", "MySQL").build()
                        : Health.down().build();
            } catch (Exception e) {
                return Health.down(e).build();
            }
        };
    }
}
