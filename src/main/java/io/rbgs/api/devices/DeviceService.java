package io.rbgs.api.devices;

import io.rbgs.api.identity.AccountRepository;
import io.rbgs.api.identity.AccountService;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class DeviceService {
    private final PairingRepository pairings;
    private final DeviceRepository devices;
    private final AccountRepository accounts;
    private final AccountService identities;
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final String SCOPE = "companion:status";
    public static final String CAPTURE_SCOPE = "companion:status companion:assignments evidence:write";

    public record Pairing(String code, String pollingSecret, Instant expiresAt, int intervalSeconds) {}
    public record Device(UUID id, String name, Instant createdAt, Instant expiresAt, Instant revokedAt, String scope) {}
    public record Token(String status, String token, UUID deviceId, Instant expiresAt, String scope) {}
    public record Status(UUID deviceId, UUID accountId, String scope) {}

    @Transactional
    public Pairing start(String name) {
        return start(name, SCOPE);
    }
    @Transactional
    public Pairing start(String name, String scope) {
        if (!SCOPE.equals(scope) && !CAPTURE_SCOPE.equals(scope)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported device scope");
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 10; i++) code.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        String polling = secret();
        PairingEntity entity = pairings.save(new PairingEntity(hash(code.toString()), hash(polling), name.strip(), Instant.now(), scope));
        return new Pairing(code.toString(), polling, entity.getExpiresAt(), 5);
    }

    @Transactional
    public void approve(Authentication authentication, String code) {
        approve(authentication, code, false);
    }
    @Transactional
    public void approve(Authentication authentication, String code, boolean acceptCapturePermissions) {
        UUID accountId = currentAccount(authentication);
        PairingEntity pairing = pairings.lockByCode(hash(code)).orElseThrow(DeviceService::unavailable);
        requirePending(pairing);
        if (pairing.getAccountId() != null) throw unavailable();
        if (CAPTURE_SCOPE.equals(pairing.getRequestedScope()) && !acceptCapturePermissions)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Explicit capture permission approval is required");
        pairing.approve(accountId);
    }




    @Transactional
    public Token exchange(String pollingSecret) {
        PairingEntity pairing = pairings.lockByPolling(hash(pollingSecret)).orElseThrow(DeviceService::unavailable);
        requirePending(pairing);
        if (pairing.getAccountId() == null) return new Token("PENDING", null, null, null, null);
        var account = accounts.findForSelection(pairing.getAccountId()).orElseThrow(DeviceService::unavailable);
        if (!"ACTIVE".equals(account.getStatus())) throw unavailable();
        Instant now = Instant.now();
        if (devices.countByAccountIdAndRevokedAtIsNullAndExpiresAtAfter(account.getId(), now) >= 5)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Revoke an existing device before pairing another");
        String token = secret();
        DeviceEntity device = devices.save(new DeviceEntity(account.getId(), pairing.getName(), hash(token), now, pairing.getRequestedScope()));
        pairing.consume(now);
        return new Token("PAIRED", token, device.getId(), device.getExpiresAt(), device.getGrantedScope());
    }

    @Transactional(readOnly = true)
    public List<Device> list(Authentication authentication) {
        return devices.findByAccountIdOrderByCreatedAtDesc(currentAccount(authentication)).stream().map(DeviceService::view).toList();
    }

    @Transactional
    public void revoke(Authentication authentication, UUID id) {
        UUID accountId = currentAccount(authentication);
        DeviceEntity device = devices.findById(id).filter(d -> d.getAccountId().equals(accountId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        device.revoke(Instant.now());
    }

    @Transactional(readOnly = true)
    public Status status(String authorization) {
        DeviceEntity device = authorizedDevice(authorization);
        return new Status(device.getId(), device.getAccountId(), device.getGrantedScope());
    }


    private DeviceEntity authorizedDevice(String authorization) {
        if (authorization == null || !authorization.matches("Bearer [A-Za-z0-9_-]{43}")) throw unauthorized();
        DeviceEntity device = devices.findByTokenHash(hash(authorization.substring(7))).orElseThrow(DeviceService::unauthorized);
        if (device.getRevokedAt() != null || !Instant.now().isBefore(device.getExpiresAt())) throw unauthorized();
        var account = accounts.findById(device.getAccountId()).orElseThrow(DeviceService::unauthorized);
        if (!"ACTIVE".equals(account.getStatus())) throw unauthorized();
        return device;
    }
    @Transactional(readOnly = true)
    public Status authorize(String authorization, String scope) {
        Status status = status(authorization);
        if (!Arrays.asList(status.scope().split(" ")).contains(scope))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Pair this device again with capture permissions");
        return status;
    }

    private UUID currentAccount(Authentication authentication) {
        var profile = identities.currentUser(authentication);
        if (profile == null) throw unauthorized();
        var account = accounts.findById(profile.id()).orElseThrow(DeviceService::unauthorized);
        if (!"ACTIVE".equals(account.getStatus())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return account.getId();
    }
    private static void requirePending(PairingEntity pairing) {
        if (pairing.getConsumedAt() != null || !Instant.now().isBefore(pairing.getExpiresAt())) throw unavailable();
    }


    private static Device view(DeviceEntity d) { return new Device(d.getId(),d.getName(),d.getCreatedAt(),d.getExpiresAt(),d.getRevokedAt(),d.getGrantedScope()); }
    private static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.GONE, "Pairing unavailable; create a new code"); }
    private static ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
    private static String secret() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
