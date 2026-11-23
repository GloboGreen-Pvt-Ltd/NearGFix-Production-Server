package com.repairshop.saas.ticket.config;

import com.repairshop.saas.common.presence.PresenceAvailability;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Presence-based assignment checks. Enforcement is OFF unless presence.assignment-enforcement.enabled=true. */
@Configuration
public class PresenceAvailabilityConfig {
    @Bean
    public PresenceAvailability presenceAvailability(JdbcTemplate jdbc,
            @Value("${presence.assignment-enforcement.enabled:false}") boolean enforcement,
            @Value("${presence.verification-timeout-seconds:90}") int timeoutSeconds) {
        return new PresenceAvailability(jdbc, enforcement, timeoutSeconds);
    }
}
