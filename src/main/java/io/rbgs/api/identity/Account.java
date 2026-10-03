package io.rbgs.api.identity;

import java.util.UUID;

public record Account(UUID id, String displayName, String region, String status, String role) {
}
