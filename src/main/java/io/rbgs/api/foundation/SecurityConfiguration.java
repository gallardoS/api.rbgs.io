package io.rbgs.api.foundation;

import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import io.rbgs.api.emailnotifications.feedback.EmailFeedbackController;

import io.rbgs.api.identity.Account;
import io.rbgs.api.identity.AccountAuthorizationManager;
import io.rbgs.api.identity.AccountService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            ObjectProvider<ClientRegistrationRepository> registrations,
            ObjectProvider<AccountService> accountServices,
            @Value("${rbgs.auth.web-origin:}") String webOrigin) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.POST, EmailFeedbackController.PATH).permitAll()
                .requestMatchers("/api/v1/health", "/openapi/v1.yaml", "/actuator/health", "/actuator/prometheus").permitAll()
                .requestMatchers("/api/v1/auth/me").access(new AccountAuthorizationManager(accountServices, null, true))
                .requestMatchers("/api/v1/season-notifications", "/api/v1/season-notifications/confirm", "/api/v1/season-notifications/unsubscribe").permitAll()
                .requestMatchers("/api/v1/characters/me").access(new AccountAuthorizationManager(accountServices, null, false))
                .requestMatchers("/api/v1/moderation/**").access(new AccountAuthorizationManager(accountServices, "MODERATOR", false))
                .anyRequest().denyAll());
        http.csrf(csrf -> csrf.ignoringRequestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, EmailFeedbackController.PATH))
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()));
        http.httpBasic(AbstractHttpConfigurer::disable);
        http.formLogin(AbstractHttpConfigurer::disable);
        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, error) -> writeProblem(response, 401, "Unauthorized"))
                .accessDeniedHandler((request, response, error) -> writeProblem(response, 403, "Forbidden")));

        if (registrations.getIfAvailable() != null) {
            String successUrl = webOrigin.isBlank() ? "/" : webOrigin + "/";
            String failureUrl = webOrigin.isBlank() ? "/?auth=failed" : webOrigin + "/?auth=failed";
            AccountService accounts = accountServices.getObject();
            OidcUserService delegate = new OidcUserService();
            http.oauth2Login(login -> login
                    .userInfoEndpoint(userInfo -> userInfo.oidcUserService(request -> {
                        var user = delegate.loadUser(request);
                        String issuer = user.getIdToken().getIssuer().toString();
                        String subject = user.getSubject();
                        String displayName = user.getClaimAsString("battletag");
                        if (displayName == null || displayName.isBlank()) {
                            displayName = "Battle.net player";
                        }
                        if (displayName.length() > 100) {
                            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info"));
                        }
                        Account account = accounts.upsert(issuer, subject, displayName);
                        if (!"ACTIVE".equals(account.status())) {
                            throw new OAuth2AuthenticationException(new OAuth2Error("account_suspended"));
                        }
                        return new DefaultOidcUser(
                                List.of(new SimpleGrantedAuthority("ROLE_" + account.role())),
                                user.getIdToken(), user.getUserInfo(), "sub");
                    }))
                    .defaultSuccessUrl(successUrl, true)
                    .failureUrl(failureUrl));
            http.logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
                    .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
        }
        return http.build();
    }

    private static void writeProblem(HttpServletResponse response, int status, String title) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status + "}");
    }

    @Bean
    UserDetailsService userDetailsService() {
        return username -> { throw new UsernameNotFoundException("No local users are configured"); };
    }
}
