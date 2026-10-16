package com.idraak.smartbite.v2.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Initialises the Firebase Admin SDK from FIREBASE_SERVICE_ACCOUNT_BASE64 (decoded in memory, never written to
 * disk). Without it, Application Default Credentials are used (useful locally with gcloud; not on Render).
 */
@Configuration
@ConditionalOnProperty(prefix = "smartbite.v2.stores", name = "mode", havingValue = "firestore")
public class FirebaseConfig {

    static final String APP_NAME = "smartbite-backend";

    /** Created after {@link DeployedConfigGuard}, so missing settings are reported by name first. */
    @Bean(destroyMethod = "delete")
    @DependsOn("deployedConfigGuard")
    public FirebaseApp firebaseApp(V2Properties props) throws IOException {
        V2Properties.Stores stores = props.stores();
        GoogleCredentials credentials;
        if (stores.serviceAccountBase64() != null && !stores.serviceAccountBase64().isBlank()) {
            byte[] json = Base64.getDecoder().decode(stores.serviceAccountBase64().trim());
            credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(json));
        } else {
            credentials = GoogleCredentials.getApplicationDefault();
        }
        FirebaseOptions.Builder options = FirebaseOptions.builder().setCredentials(credentials);
        if (stores.firebaseProjectId() != null && !stores.firebaseProjectId().isBlank()) {
            options.setProjectId(stores.firebaseProjectId());
        }
        return FirebaseApp.initializeApp(options.build(), APP_NAME);
    }
}
