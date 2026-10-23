package io.rbgs.api.matches;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MatchAvailability {
    private final boolean controlledEnabled;

    public MatchAvailability(@Value("${rbgs.capture.controlled-enabled:false}") boolean controlledEnabled) {
        this.controlledEnabled = controlledEnabled;
    }

    public boolean controlledEnabled() { return controlledEnabled; }

    public void requireControlledEnabled() {
        if (!controlledEnabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Test matches are currently unavailable");
    }

    public void requireAvailable(MatchEntity match) {
        if (match.getMode() == MatchMode.CONTROLLED_TEST) requireControlledEnabled();
    }
}
