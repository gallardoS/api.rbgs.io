package io.rbgs.api.foundation;

import java.sql.SQLException;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HealthReadinessIntegrationTests {
    @Autowired MockMvc mvc;
    @MockitoSpyBean DataSource dataSource;

    @Test
    void databaseConnectionFailureAfterStartupLeavesLivenessUpAndRecoversReadiness() throws Exception {
        mvc.perform(get("/api/v1/readiness")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"ready\"}"));
        try {
            doThrow(new SQLException("Simulated database connection outage")).when(dataSource).getConnection();
            mvc.perform(get("/api/v1/readiness")).andExpect(status().isServiceUnavailable())
                    .andExpect(content().json("{\"status\":\"unavailable\"}"));
            mvc.perform(get("/api/v1/health")).andExpect(status().isOk())
                    .andExpect(content().json("{\"status\":\"ok\"}"));
        } finally {
            reset(dataSource);
        }
        mvc.perform(get("/api/v1/readiness")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"ready\"}"));
    }
}
