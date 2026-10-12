package io.rbgs.api.evidence;

import com.github.benmanes.caffeine.cache.*;
import io.rbgs.api.devices.DeviceService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class CaptureController {
    private final CaptureService captures;private final CaptureJson json;private final DeviceService devices;
    private final Cache<String,AtomicInteger> limits=Caffeine.newBuilder().maximumSize(10000).expireAfterWrite(Duration.ofMinutes(1)).build();
    @PostMapping("/api/v1/moderation/capture-tests")
    public ResponseEntity<?> create(Authentication auth,HttpServletRequest request)throws IOException {limit("create:"+auth.getName(),10);return response(captures.create(auth,body(request)));}
    @PostMapping("/api/v1/moderation/capture-tests/{id}/cancel")
    public ResponseEntity<Void> cancel(Authentication auth,@PathVariable UUID id){captures.cancel(auth,id);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
    @GetMapping("/api/v1/companion/assignments")
    public ResponseEntity<?> list(@RequestHeader(value="Authorization",required=false)String authorization){var reporter=devices.authorize(authorization,"companion:assignments");limit("read:"+reporter.accountId(),120);return response(captures.list(reporter.accountId()));}
    @GetMapping("/api/v1/companion/assignments/{captureId}")
    public ResponseEntity<?> descriptor(@RequestHeader(value="Authorization",required=false)String authorization,@PathVariable UUID captureId){var reporter=devices.authorize(authorization,"companion:assignments");limit("read:"+reporter.accountId(),120);return response(captures.descriptor(reporter.accountId(),captureId));}
    @PostMapping("/api/v1/companion/assignments/{captureId}/issue")
    public ResponseEntity<?> issue(@RequestHeader(value="Authorization",required=false)String authorization,@PathVariable UUID captureId){var reporter=devices.authorize(authorization,"companion:assignments");limit("issue:"+reporter.accountId(),10);return response(captures.issue(reporter.accountId(),captureId));}
    @PostMapping("/api/v1/evidence")
    public ResponseEntity<CaptureService.Receipt> ingest(@RequestHeader(value="Authorization",required=false)String authorization,HttpServletRequest request)throws IOException {
        var reporter=devices.authorize(authorization,"evidence:write");limit("write:"+reporter.accountId(),60);return response(captures.ingest(reporter,body(request)));}
    @GetMapping("/api/v1/evidence/receipts/{assignmentId}/{captureId}/{revision}")
    public ResponseEntity<CaptureService.Receipt> recover(@RequestHeader(value="Authorization",required=false)String authorization,@PathVariable UUID assignmentId,@PathVariable UUID captureId,@PathVariable int revision){var reporter=devices.authorize(authorization,"evidence:write");limit("read:"+reporter.accountId(),120);return response(captures.recover(reporter.accountId(),assignmentId,captureId,revision));}
    private Map<String,Object> body(HttpServletRequest request)throws IOException {
        if(request.getContentType()==null||!MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType())))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        byte[] bytes=request.getInputStream().readNBytes(CaptureJson.MAX_BYTES+1);if(bytes.length>CaptureJson.MAX_BYTES)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Capture request exceeds 256 KiB");return json.parse(bytes);}
    private void limit(String key,int maximum){if(limits.get(key,k->new AtomicInteger()).incrementAndGet()>maximum)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Retry in one minute");}
    private static <T>ResponseEntity<T> response(T value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
}
