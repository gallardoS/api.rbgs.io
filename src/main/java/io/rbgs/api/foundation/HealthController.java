package io.rbgs.api.foundation;

import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {
    private final HealthEndpoint dependencies;

    public HealthController(HealthEndpoint dependencies) {
        this.dependencies = dependencies;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("ok");
    }

    @GetMapping("/readiness")
    public ResponseEntity<HealthResponse> readiness() {
        var database = dependencies.healthForPath("db");
        boolean ready = database != null && Status.UP.equals(database.getStatus());
        return ResponseEntity.status(ready ? 200 : 503).cacheControl(CacheControl.noStore())
                .body(new HealthResponse(ready ? "ready" : "unavailable"));
    }

    public record HealthResponse(String status) {}
}
