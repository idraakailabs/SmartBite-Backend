package com.idraak.smartbite.v2.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fails fast when a deployed instance is misconfigured, instead of starting and failing on the first request.
 *
 * <ul>
 *   <li>On Render (which sets {@code RENDER=true}) a {@code prod} or {@code staging} profile is required: without
 *       one, none of the deployed settings (structured logs, Firestore prefix) apply.</li>
 *   <li>With {@code prod} or {@code staging}, every required secret must be set. Only variable <em>names</em> are
 *       reported, never values.</li>
 * </ul>
 * Render keeps the previous deploy running when a new one fails to start, so this turns a bad configuration into
 * a failed deploy rather than an outage. See docs/ops/DEPLOYMENT.md.
 */
@Component
public class DeployedConfigGuard {

    private final Environment env;
    private final V2Properties props;

    public DeployedConfigGuard(Environment env, V2Properties props) {
        this.env = env;
        this.props = props;
    }

    @PostConstruct
    void check() {
        boolean deployed = env.acceptsProfiles(Profiles.of("prod | staging"));
        if ("true".equalsIgnoreCase(env.getProperty("RENDER")) && !deployed) {
            throw new IllegalStateException("Running on Render without a deployed profile: set SPRING_PROFILES_ACTIVE to prod or staging.");
        }
        if (!deployed) return;
        List<String> missing = missingSettings();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required settings for a deployed profile: " + String.join(", ", missing)
                    + ". See docs/ops/ENVIRONMENT.md §2.");
        }
    }

    List<String> missingSettings() {
        List<String> missing = new ArrayList<>();
        V2Properties.Stores stores = props.stores();
        if (blank(stores.firebaseProjectId())) missing.add("FIREBASE_PROJECT_ID");
        if (blank(stores.serviceAccountBase64()) && blank(env.getProperty("GOOGLE_APPLICATION_CREDENTIALS"))) {
            missing.add("FIREBASE_SERVICE_ACCOUNT_BASE64");
        }
        if (blank(props.logging().uidHashKey())) missing.add("LOG_UID_HASH_KEY");
        if (blank(props.ai().geminiApiKey())) missing.add("GEMINI_API_KEY");
        V2Properties.Entitlement entitlement = props.entitlement();
        if ("revenuecat".equalsIgnoreCase(entitlement.provider())) {
            if (blank(entitlement.secretApiKey())) missing.add("REVENUECAT_SECRET_API_KEY");
            if (blank(entitlement.projectId())) missing.add("REVENUECAT_PROJECT_ID");
        }
        if ("google".equalsIgnoreCase(props.places().provider()) && blank(props.places().apiKey())) {
            missing.add("GOOGLE_PLACES_API_KEY");
        }
        return missing;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
