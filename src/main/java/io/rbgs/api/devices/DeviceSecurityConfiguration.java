package io.rbgs.api.devices;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class DeviceSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain deviceTransport(HttpSecurity http) throws Exception {
        // These exact routes never authenticate with cookies. Approval and revocation use the web chain.
        http.securityMatcher("/api/v1/device-pairings", "/api/v1/device-pairings/token", "/api/v1/companion/status", "/api/v1/companion/heartbeat",
                "/api/v1/companion/assignments", "/api/v1/companion/assignments/**", "/api/v1/evidence", "/api/v1/evidence/receipts/**")
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        return http.build();
    }
}
