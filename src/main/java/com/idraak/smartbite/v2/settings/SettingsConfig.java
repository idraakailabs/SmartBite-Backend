package com.idraak.smartbite.v2.settings;

import com.google.firebase.FirebaseApp;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException;
import com.google.firebase.remoteconfig.Parameter;
import com.google.firebase.remoteconfig.ParameterValue;
import com.google.firebase.remoteconfig.Template;
import com.idraak.smartbite.v2.config.V2Properties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

@Configuration
@EnableScheduling
public class SettingsConfig {

    @Bean
    public RemoteConfigAppSettings appSettings(V2Properties props, ObjectProvider<FirebaseApp> app, TaskScheduler scheduler) {
        V2Properties.Quota q = props.quota();
        Supplier<Map<String, String>> reader = null;
        FirebaseApp firebaseApp = app.getIfAvailable();
        if (q.remoteConfigSyncEnabled() && firebaseApp != null) {
            FirebaseRemoteConfig rc = FirebaseRemoteConfig.getInstance(firebaseApp);
            reader = () -> readDefaults(rc);
        }
        RemoteConfigAppSettings settings = new RemoteConfigAppSettings(q.freeDailySuggestions(), props.recipes().stepsPremium(), reader);
        if (reader != null) {
            scheduler.scheduleWithFixedDelay(settings::refresh, q.remoteConfigRefresh());
        }
        return settings;
    }

    static Map<String, String> readDefaults(FirebaseRemoteConfig rc) {
        try {
            Template template = rc.getTemplate();
            Map<String, String> out = new HashMap<>();
            for (Map.Entry<String, Parameter> e : template.getParameters().entrySet()) {
                if (e.getValue().getDefaultValue() instanceof ParameterValue.Explicit explicit) {
                    out.put(e.getKey(), explicit.getValue());
                }
            }
            return out;
        } catch (FirebaseRemoteConfigException e) {
            throw new IllegalStateException("Remote Config template read failed", e);
        }
    }
}
