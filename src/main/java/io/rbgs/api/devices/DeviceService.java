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

    public record Pairing(String code, String pollingSecret, Instant expiresAt, int intervalSeconds, String approvalSecret) {}
    public record Device(UUID id, Instant createdAt, Instant expiresAt, Instant revokedAt, String scope, Instant lastSeenAt, boolean connected) {}
    public record BrowserPairing(String scope, Instant expiresAt, String status, UUID deviceId, String accountName) {}
    public record Token(String status, String token, UUID deviceId, Instant expiresAt, String scope) {}
    public record Status(UUID deviceId, UUID accountId, String scope, String accountName) {}
    private record AuthorizedDevice(DeviceEntity device, String accountName) {}

    @Transactional
    public Pairing start() {
        return start(SCOPE);
    }
    @Transactional
    public Pairing start(String scope) {
        if (!SCOPE.equals(scope) && !CAPTURE_SCOPE.equals(scope)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported device scope");
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 10; i++) code.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        String polling = secret();
        String approval = secret();
        PairingEntity entity = new PairingEntity(hash(code.toString()), hash(polling), Instant.now(), scope);
        entity.browserApproval(hash(approval)); pairings.save(entity);
        return new Pairing(code.toString(), polling, entity.getExpiresAt(), 5, approval);
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
    public BrowserPairing browserPairing(Authentication auth, String approvalSecret, boolean approve, boolean captureConsent) {
        UUID account = currentAccount(auth);
        var pairing = pairings.lockByApproval(hash(approvalSecret)).orElseThrow(DeviceService::unavailable);
        if (!Instant.now().isBefore(pairing.getExpiresAt()) || (pairing.getAccountId()!=null && !account.equals(pairing.getAccountId()))) throw unavailable();
        if (approve) {
            requirePending(pairing);
            if (pairing.getAccountId()!=null) throw unavailable();
            if (CAPTURE_SCOPE.equals(pairing.getRequestedScope()) && !captureConsent)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Explicit capture permission approval is required");
            pairing.approve(account);
        }
        return new BrowserPairing(pairing.getRequestedScope(),pairing.getExpiresAt(),
                pairing.getDeviceId()!=null?"PAIRED":pairing.getAccountId()!=null?"APPROVED":"PENDING",pairing.getDeviceId(),identities.currentUser(auth).displayName());
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
        DeviceEntity device = devices.save(new DeviceEntity(account.getId(), hash(token), now, pairing.getRequestedScope()));
        pairing.consume(now);
        pairing.issuedDevice(device.getId());
        return new Token("PAIRED", token, device.getId(), device.getExpiresAt(), device.getGrantedScope());
    }

    @Transactional(readOnly = true)
    public List<Device> list(Authentication authentication) {
        return devices.findActiveByAccountId(currentAccount(authentication), Instant.now()).stream().map(DeviceService::view).toList();
    }

    @Transactional
    public void requireLinkedDevice(UUID accountId, UUID deviceId) {
        DeviceEntity device = deviceId == null ? null : devices.findByIdAndAccountId(deviceId, accountId).orElse(null);
        if (device == null || device.getRevokedAt() != null || !Instant.now().isBefore(device.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "A valid linked companion is required");
        }
    }

    @Transactional
    public void revoke(Authentication authentication, UUID id) {
        UUID accountId = currentAccount(authentication);
        DeviceEntity device = devices.lockById(id).filter(d -> d.getAccountId().equals(accountId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        device.revoke(Instant.now());
    }

    @Transactional
    public void removeInactive(Authentication authentication, UUID id) {
        UUID accountId = currentAccount(authentication);
        DeviceEntity device = devices.lockById(id).filter(d -> d.getAccountId().equals(accountId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Instant now = Instant.now();
        if (device.getRevokedAt() == null && now.isBefore(device.getExpiresAt()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Revoke access before removing this device");
        device.removeFromListing(now);
    }

    @Transactional(readOnly = true)
    public Status status(String authorization) {
        var authorized = authorizedDevice(authorization);
        var device = authorized.device();
        return new Status(device.getId(), device.getAccountId(), device.getGrantedScope(), authorized.accountName());
    }
    @Transactional
    public Status heartbeat(String authorization) {
        var authorized = authorizedDevice(authorization);
        var device = authorized.device();
        device.heartbeat(Instant.now());
        return new Status(device.getId(),device.getAccountId(),device.getGrantedScope(),authorized.accountName());
    }
    private AuthorizedDevice authorizedDevice(String authorization) {
        if (authorization == null || !authorization.matches("Bearer [A-Za-z0-9_-]{43}")) throw unauthorized();
        DeviceEntity device = devices.findByTokenHash(hash(authorization.substring(7))).orElseThrow(DeviceService::unauthorized);
        if (device.getRevokedAt() != null || !Instant.now().isBefore(device.getExpiresAt())) throw unauthorized();
        var account = accounts.findById(device.getAccountId()).orElseThrow(DeviceService::unauthorized);
        if (!"ACTIVE".equals(account.getStatus())) throw unauthorized();
        return new AuthorizedDevice(device, account.getDisplayName());
    }
    @Transactional(readOnly = true)
    public Status authorize(String authorization, String scope) {
        Status status = status(authorization);
        if (!Arrays.asList(status.scope().split(" ")).contains(scope))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Pair this device again with capture permissions");
        return status;
    }

    UUID currentAccount(Authentication authentication) {
        var profile = identities.currentUser(authentication);
        if (profile == null) throw unauthorized();
        var account = accounts.findById(profile.id()).orElseThrow(DeviceService::unauthorized);
        if (!"ACTIVE".equals(account.getStatus())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return account.getId();
    }
    private static void requirePending(PairingEntity pairing) {
        if (pairing.getConsumedAt() != null || !Instant.now().isBefore(pairing.getExpiresAt())) throw unavailable();
    }
    private static Device view(DeviceEntity d) { Instant now=Instant.now();boolean connected=d.getRevokedAt()==null && now.isBefore(d.getExpiresAt()) && d.getLastSeenAt()!=null && !d.getLastSeenAt().isAfter(now) && d.getLastSeenAt().isAfter(now.minusSeconds(45));
        return new Device(d.getId(), d.getCreatedAt(), d.getExpiresAt(), d.getRevokedAt(), d.getGrantedScope(),d.getLastSeenAt(),connected); }
    private static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.GONE, "Pairing unavailable; create a new code"); }
    private static ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
    static String secret() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
