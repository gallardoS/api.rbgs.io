package io.rbgs.api.emailnotifications.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class EmailRuntimeConfiguration {
    @Bean
    Clock emailClock() { return Clock.systemUTC(); }
}
