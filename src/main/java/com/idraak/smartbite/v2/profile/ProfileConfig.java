package com.idraak.smartbite.v2.profile;

import com.google.firebase.FirebaseApp;
import com.google.firebase.cloud.FirestoreClient;
import com.idraak.smartbite.v2.config.V2Properties;
import com.idraak.smartbite.v2.web.UidHasher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class ProfileConfig {

    /** The app's legacy Firestore profiles when Firebase is configured; none otherwise (tests and local runs). */
    @Bean
    @ConditionalOnMissingBean
    public LegacyProfileSource legacyProfileSource(ObjectProvider<FirebaseApp> app, V2Properties props) {
        FirebaseApp firebase = app.getIfAvailable();
        if (firebase == null) return LegacyProfileSource.NONE;
        return new FirestoreLegacyProfileSource(FirestoreClient.getFirestore(firebase), props.stores().operationTimeout());
    }

    /** Without a database the service exists but answers 503 FEATURE_UNAVAILABLE. */
    @Bean
    public ProfileService profileService(ObjectProvider<JdbcTemplate> jdbc, LegacyProfileSource legacy, ObjectMapper mapper,
                                         V2Properties props, UidHasher hasher) {
        JdbcTemplate template = jdbc.getIfAvailable();
        return new ProfileService(template == null ? null : new ProfileRepository(template, mapper), legacy, mapper,
                props.history(), hasher);
    }
}
