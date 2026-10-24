package io.rbgs.api.identity;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Profile("local")
public class ApiClientLoginSecurityConfiguration {
    @Bean
    @Order(2)
    SecurityFilterChain apiClientTransport(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/v1/auth/api-client/requests", "/api/v1/auth/api-client/token")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll()).build();
    }
}
