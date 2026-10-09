package io.rbgs.api.foundation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.contributor.Status;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@WebMvcTest(HealthController.class)
@Import(SecurityConfiguration.class)
class SecurityConfigurationTests {

    @Autowired
    private MockMvc mvc;
    @MockitoBean HealthEndpoint dependencies;

    @Test
    void healthIsPublicAndUnknownApiPathsAreDeniedAsProblems() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        mvc.perform(get("/api/v1/private"))
                .andExpect(status().is4xxClientError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").isNumber());
    }

    @Test
    void readinessIsPublicAndOnlyRevealsAnUncachedStatus() throws Exception {
        HealthDescriptor database = mock(IndicatedHealthDescriptor.class);
        when(dependencies.healthForPath("db")).thenReturn(database);
        when(database.getStatus()).thenReturn(Status.UP);
        mvc.perform(get("/api/v1/readiness")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"ready\"}"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void unavailableUnknownOrMissingDatabaseNeverReportsReadiness() throws Exception {
        HealthDescriptor database = mock(IndicatedHealthDescriptor.class);
        when(dependencies.healthForPath("db")).thenReturn(database);
        for (Status state : new Status[] {Status.DOWN, Status.UNKNOWN, Status.OUT_OF_SERVICE}) {
            when(database.getStatus()).thenReturn(state);
            mvc.perform(get("/api/v1/readiness")).andExpect(status().isServiceUnavailable())
                    .andExpect(content().json("{\"status\":\"unavailable\"}"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        when(dependencies.healthForPath("db")).thenReturn(null);
        mvc.perform(get("/api/v1/readiness")).andExpect(status().isServiceUnavailable());
    }
}
