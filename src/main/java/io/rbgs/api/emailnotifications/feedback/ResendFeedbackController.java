package io.rbgs.api.emailnotifications.feedback;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ResendFeedbackController {
    public static final String PATH = "/api/v1/email-notifications/feedback/resend";
    private static final int MAX_BODY_BYTES = 256 * 1024;
    private final ResendFeedbackProcessor processor;

    public ResendFeedbackController(ResendFeedbackProcessor processor) { this.processor = processor; }

    @PostMapping(PATH)
    public ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Webhook message too large");
        processor.process(new String(body, StandardCharsets.UTF_8), request.getHeader("svix-id"),
                request.getHeader("svix-timestamp"), request.getHeader("svix-signature"));
        return ResponseEntity.noContent().build();
    }
}
