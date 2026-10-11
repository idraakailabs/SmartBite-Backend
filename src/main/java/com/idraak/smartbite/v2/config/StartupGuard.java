package com.idraak.smartbite.v2.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Refuses to start with settings that are only safe in tests: in-memory stores (quota would reset on every
 * restart) or fake tokens (anyone could be anyone). Both need {@code allow-in-memory=true}, which only the test
 * profile and local development set.
 */
@Component
public class StartupGuard {

    private final V2Properties props;

    public StartupGuard(V2Properties props) {
        this.props = props;
    }

    @PostConstruct
    void check() {
        boolean allow = props.stores().allowInMemory();
        if (!props.stores().firestore() && !allow) {
            throw new IllegalStateException("smartbite.v2.stores.mode=" + props.stores().mode()
                    + " is only allowed with smartbite.v2.stores.allow-in-memory=true (tests/local).");
        }
        if (props.auth().fakeTokensEnabled() && !allow) {
            throw new IllegalStateException("Fake ID tokens are only allowed with smartbite.v2.stores.allow-in-memory=true.");
        }
        if (props.places().cacheTtl().toDays() > 30) {
            throw new IllegalStateException("PLACES_CACHE_TTL must be <= 30 days (Google Maps Service Specific Terms §14.3).");
        }
    }
}
