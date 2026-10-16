package io.rbgs.api.devices;

import io.rbgs.api.identity.AccountRepository;
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
public class DeviceLaunchService {
    private final DeviceLaunchRepository launches;
    private final DeviceRepository devices;
    private final DeviceService connections;
    private final AccountRepository accounts;
    public record Launch(UUID id, String launchSecret, Instant expiresAt) {}
    public record Progress(String status, UUID deviceId, Instant expiresAt) {}

    @Transactional
    public Launch start(Authentication auth, boolean captureConsent) {
        if (!captureConsent) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Explicit capture permission approval is required");
        UUID account=connections.currentAccount(auth);
        String secret=DeviceService.secret();
        var launch=launches.save(new DeviceLaunchEntity(account,DeviceService.hash(secret),Instant.now()));
        return new Launch(launch.getId(),secret,launch.getExpiresAt());
    }

    @Transactional(readOnly=true)
    public Progress progress(Authentication auth, UUID id) {
        UUID account=connections.currentAccount(auth);
        var launch=launches.findById(id).filter(l->account.equals(l.getAccountId())).orElseThrow(DeviceLaunchService::gone);
        requireLive(launch);
        var device=launch.getDeviceId()==null?null:devices.findById(launch.getDeviceId()).orElse(null);
        if (device!=null) requireDevice(device);
        boolean connected=device!=null && device.getLastSeenAt()!=null
                && !device.getLastSeenAt().isBefore(launch.getCreatedAt()) && !device.getLastSeenAt().isAfter(Instant.now());
        return new Progress(connected?"PAIRED":device!=null?"CONNECTING":"PENDING",connected?device.getId():null,launch.getExpiresAt());
    }

    @Transactional
    public DeviceService.Token claim(String secret, String clientSecret, String authorization) {
        var launch=launches.findBySecretHash(DeviceService.hash(secret)).orElseThrow(DeviceLaunchService::gone);
        requireLive(launch);
        var account=accounts.findForSelection(launch.getAccountId()).orElseThrow(DeviceLaunchService::gone);
        if (!"ACTIVE".equals(account.getStatus())) throw gone();
        String claimHash=DeviceService.hash(clientSecret);
        if (launch.getClaimHash()!=null) {
            if (!MessageDigest.isEqual(claimHash.getBytes(StandardCharsets.US_ASCII),launch.getClaimHash().getBytes(StandardCharsets.US_ASCII))) throw gone();
            var device=devices.lockById(launch.getDeviceId()).orElseThrow(DeviceLaunchService::gone);
            requireDevice(device);
            return token(device,launch.isIssuedCredential()?credential(secret,clientSecret):null);
        }
        DeviceEntity device;
        if (authorization!=null) {
            if(!authorization.matches("Bearer [A-Za-z0-9_-]{43}")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            device=devices.lockByTokenHash(DeviceService.hash(authorization.substring(7)))
                    .orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));
            if(device.getRevokedAt()!=null || !Instant.now().isBefore(device.getExpiresAt()))
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            if (!account.getId().equals(device.getAccountId()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Companion is connected to another account");
            device.grantCapturePermissions();
        } else {
            if (devices.countByAccountIdAndRevokedAtIsNullAndExpiresAtAfter(account.getId(),Instant.now())>=5)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Revoke an existing device before pairing another");
            device=devices.save(new DeviceEntity(account.getId(),DeviceService.hash(credential(secret,clientSecret)),Instant.now(),DeviceService.CAPTURE_SCOPE));
        }
        launch.claim(claimHash,device.getId(),authorization==null);
        return token(device,authorization==null?credential(secret,clientSecret):null);
    }
    // Both random secrets are required to recover a lost native response. Neither credential nor client secret is returned to the browser.
    private static String credential(String launch, String client) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(("rbgs-web-connect-v1\n"+launch+"\n"+client).getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static DeviceService.Token token(DeviceEntity d,String token) {
        return new DeviceService.Token("PAIRED",token,d.getId(),d.getExpiresAt(),d.getGrantedScope());
    }
    private static void requireDevice(DeviceEntity d) {
        if(d.getRevokedAt()!=null || !Instant.now().isBefore(d.getExpiresAt())) throw gone();
    }
    private static void requireLive(DeviceLaunchEntity l) { if(!Instant.now().isBefore(l.getExpiresAt())) throw gone(); }
    private static ResponseStatusException gone() { return new ResponseStatusException(HttpStatus.GONE,"Connection request unavailable; open the companion again from the website"); }
}
