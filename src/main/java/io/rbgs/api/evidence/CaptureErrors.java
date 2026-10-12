package io.rbgs.api.evidence;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestControllerAdvice(basePackageClasses=CaptureController.class)
public class CaptureErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> error(ResponseStatusException error){var result=ResponseEntity.status(error.getStatusCode()).cacheControl(CacheControl.noStore());if(error.getStatusCode().value()==429)result.header("Retry-After","60");
        return result.body(ProblemDetail.forStatusAndDetail(error.getStatusCode(),error.getReason()==null?"Capture request unavailable":error.getReason()));}
}
