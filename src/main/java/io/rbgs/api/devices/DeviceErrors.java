package io.rbgs.api.devices;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackageClasses = DeviceController.class)
public class DeviceErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handle(ResponseStatusException error) {
        var response = ResponseEntity.status(error.getStatusCode()).cacheControl(CacheControl.noStore());
        if (error.getStatusCode().value() == 429) response.header("Retry-After", "60");
        return response.body(ProblemDetail.forStatusAndDetail(error.getStatusCode(),
                error.getReason() == null ? "Device access unavailable" : error.getReason()));
    }
}
