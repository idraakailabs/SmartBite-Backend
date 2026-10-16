package com.idraak.smartbite.v2.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deployed profiles load the intended settings, and {@link DeployedConfigGuard} refuses to start a deployed
 * instance that is missing a required secret. Uses the real application*.yaml files.
 */
class DeployedConfigTest {

    /** Every secret a deployed instance needs, with dummy values. */
    static Map<String, Object> allSecrets() {
        Map<String, Object> m = new HashMap<>();
        m.put("GEMINI_API_KEY", "dummy");
        m.put("FIREBASE_PROJECT_ID", "dummy-project");
        m.put("FIREBASE_SERVICE_ACCOUNT_BASE64", "ZHVtbXk=");
        m.put("LOG_UID_HASH_KEY", "ZHVtbXk=");
        m.put("REVENUECAT_SECRET_API_KEY", "dummy");
        m.put("REVENUECAT_PROJECT_ID", "dummy");
        return m;
    }

    static StandardEnvironment environment(Map<String, Object> vars, String... profiles) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("vars", vars));
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        // Profile files win over the base file, as in Spring Boot.
        for (String profile : profiles) {
            loader.load(profile, new ClassPathResource("application-" + profile + ".yaml")).forEach(env.getPropertySources()::addLast);
        }
        loader.load("base", new ClassPathResource("application.yaml")).forEach(env.getPropertySources()::addLast);
        env.setActiveProfiles(profiles);
        return env;
    }

    static V2Properties bind(StandardEnvironment env) {
        return Binder.get(env).bind("smartbite.v2", V2Properties.class).get();
    }

    static DeployedConfigGuard guard(Map<String, Object> vars, String... profiles) throws IOException {
        StandardEnvironment env = environment(vars, profiles);
        return new DeployedConfigGuard(env, bind(env));
    }

    @Test
    void prodWritesToUnprefixedCollectionsAndDeletesRevenueCatCustomers() throws IOException {
        V2Properties p = bind(environment(allSecrets(), "prod"));
        assertThat(p.stores().firestore()).isTrue();
        assertThat(p.stores().collectionPrefix()).isEmpty();
        assertThat(p.deletion().billingDeletionEnabled()).isTrue();
        assertThat(p.auth().fakeTokensEnabled()).isFalse();
    }

    @Test
    void stagingUsesItsOwnCollectionsAndNeverDeletesRevenueCatCustomers() throws IOException {
        V2Properties p = bind(environment(allSecrets(), "staging"));
        assertThat(p.stores().firestore()).isTrue();
        assertThat(p.stores().collectionPrefix()).isEqualTo("stg_");
        assertThat(p.deletion().billingDeletionEnabled()).isFalse();
    }

    @Test
    void aCompleteDeployedConfigurationStarts() throws IOException {
        assertThatCode(() -> guard(allSecrets(), "prod").check()).doesNotThrowAnyException();
        assertThatCode(() -> guard(allSecrets(), "staging").check()).doesNotThrowAnyException();
    }

    @Test
    void aMissingSecretStopsStartupAndNamesOnlyTheVariable() throws IOException {
        Map<String, Object> vars = allSecrets();
        vars.remove("LOG_UID_HASH_KEY");
        vars.remove("REVENUECAT_SECRET_API_KEY");

        assertThatThrownBy(() -> guard(vars, "prod").check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOG_UID_HASH_KEY")
                .hasMessageContaining("REVENUECAT_SECRET_API_KEY")
                .hasMessageNotContaining("dummy");
    }

    @Test
    void googlePlacesNeedsItsKey() throws IOException {
        Map<String, Object> vars = allSecrets();
        vars.put("PLACES_PROVIDER", "google");
        assertThat(guard(vars, "prod").missingSettings()).containsExactly("GOOGLE_PLACES_API_KEY");
    }

    @Test
    void onRenderAProfileIsRequired() throws IOException {
        Map<String, Object> vars = allSecrets();
        vars.put("RENDER", "true");
        assertThatThrownBy(() -> guard(vars).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_PROFILES_ACTIVE");
        assertThatCode(() -> guard(vars, "staging").check()).doesNotThrowAnyException();
    }

    @Test
    void localAndTestRunsAreNotChecked() throws IOException {
        assertThatCode(() -> guard(new HashMap<>()).check()).doesNotThrowAnyException();
    }
}
