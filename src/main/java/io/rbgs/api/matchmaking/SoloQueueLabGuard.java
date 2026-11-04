package io.rbgs.api.matchmaking;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("local & soloq-lab")
@RequiredArgsConstructor
class SoloQueueLabGuard {
    private final SoloQueueLabRepository lab;
    private final Environment environment;
    @PostConstruct void check() {
        validate(lab.databaseName(),environment.getProperty("server.address"));
        if (environment.getProperty("rbgs.notifications.sending-enabled",Boolean.class,false)
                || environment.getProperty("rbgs.capture.controlled-enabled",Boolean.class,false))
            throw new IllegalStateException("The simulation cannot enable email or game capture");
    }
    static void validate(String database,String address) {
        if (!(database.equals("rbgs_soloq_lab") || database.equals("rbgs_soloq_lab_tests"))
                || !"127.0.0.1".equals(address))
            throw new IllegalStateException("SoloQ lab requires its dedicated database and loopback binding");
    }
}
