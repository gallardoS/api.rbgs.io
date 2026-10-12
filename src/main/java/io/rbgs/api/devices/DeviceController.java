package io.rbgs.api.devices;

import com.github.benmanes.caffeine.cache.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class DeviceController {
    private final DeviceService devices;
    private final Cache<String, AtomicInteger> limits = Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(Duration.ofMinutes(1)).build();
    public record Start(@NotBlank @Size(max = 80) @Pattern(regexp = "[^\\p{Cntrl}]+") String name, @Size(max=128) String scope) {}
    public record Approval(@NotNull @Pattern(regexp = "[A-HJ-NP-Z2-9]{10}") String code, Boolean acceptCapturePermissions) {}
    public record Poll(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String pollingSecret) {}
    public record BrowserApproval(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String approvalSecret, Boolean acceptCapturePermissions) {}

    @PostMapping("/api/v1/device-pairings/browser/details")
    public ResponseEntity<DeviceService.BrowserPairing> details(Authentication auth,@Valid @RequestBody BrowserApproval request) {
        limit("browser-read:"+auth.getName(),120);
        return response(devices.browserPairing(auth,request.approvalSecret(),false,false));
    }
    @PostMapping("/api/v1/device-pairings/browser/approve")
    public ResponseEntity<DeviceService.BrowserPairing> approveBrowser(Authentication auth,@Valid @RequestBody BrowserApproval request) {
        limit("approve:"+auth.getName(),10);
        return response(devices.browserPairing(auth,request.approvalSecret(),true,Boolean.TRUE.equals(request.acceptCapturePermissions())));
    }

    @PostMapping("/api/v1/device-pairings")
    public ResponseEntity<DeviceService.Pairing> start(@Valid @RequestBody Start request, HttpServletRequest servlet) {
        limit("start:" + servlet.getRemoteAddr(), 5);
        return response(devices.start(request.name(), request.scope() == null ? DeviceService.SCOPE : request.scope()));
    }
    @PostMapping("/api/v1/device-pairings/approve")
    public ResponseEntity<Void> approve(Authentication auth, @Valid @RequestBody Approval request) {
        limit("approve:" + auth.getName(), 10);
        devices.approve(auth, request.code(), Boolean.TRUE.equals(request.acceptCapturePermissions()));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping("/api/v1/device-pairings/token")
    public ResponseEntity<DeviceService.Token> token(@Valid @RequestBody Poll request, HttpServletRequest servlet) {
        limit("poll-ip:" + servlet.getRemoteAddr(), 120);
        limit("poll:" + DeviceService.hash(request.pollingSecret()), 15);
        return response(devices.exchange(request.pollingSecret()));
    }
    @GetMapping("/api/v1/devices/me")
    public ResponseEntity<List<DeviceService.Device>> list(Authentication auth, CsrfToken csrf) {
        csrf.getToken();
        return response(devices.list(auth));
    }
    @DeleteMapping("/api/v1/devices/{id}")
    public ResponseEntity<Void> revoke(Authentication auth, @PathVariable UUID id) {
        devices.revoke(auth, id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/api/v1/companion/status")
    public ResponseEntity<DeviceService.Status> status(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return response(devices.status(authorization));
    }
    @PostMapping("/api/v1/companion/heartbeat")
    public ResponseEntity<DeviceService.Status> heartbeat(@RequestHeader(value="Authorization",required=false) String authorization) {
        var reporter=devices.status(authorization);limit("heartbeat:"+reporter.deviceId(),12);
        return response(devices.heartbeat(authorization));
    }
    private void limit(String key, int maximum) {
        if (limits.get(key, ignored -> new AtomicInteger()).incrementAndGet() > maximum)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Retry in one minute");
    }
    private static <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
