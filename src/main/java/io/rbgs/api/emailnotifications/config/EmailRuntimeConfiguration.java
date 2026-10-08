package io.rbgs.api.emailnotifications.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmailRuntimeConfiguration {
    @Bean
    Clock emailClock() { return Clock.systemUTC(); }
}
