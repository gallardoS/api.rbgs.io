package io.rbgs.api.identity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiClientLoginProfileTests {
    @ParameterizedTest
    @ValueSource(strings = {"", "prod"})
    void handoffBeansAreAbsentUnlessLocalProfileIsActive(String profile) {
        try (var context = new AnnotationConfigApplicationContext()) {
            if (!profile.isEmpty()) context.getEnvironment().setActiveProfiles(profile);
            context.register(ApiClientLoginController.class, ApiClientLoginService.class, ApiClientLoginSecurityConfiguration.class);
            context.refresh();
            assertTrue(context.getBeansOfType(ApiClientLoginController.class).isEmpty());
            assertTrue(context.getBeansOfType(ApiClientLoginService.class).isEmpty());
            assertTrue(context.getBeansOfType(ApiClientLoginSecurityConfiguration.class).isEmpty());
        }
    }
}
