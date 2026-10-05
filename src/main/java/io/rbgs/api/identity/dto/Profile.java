package io.rbgs.api.identity.dto;

import java.util.UUID;

public record Profile(UUID id, String displayName, String region, String role) { }
