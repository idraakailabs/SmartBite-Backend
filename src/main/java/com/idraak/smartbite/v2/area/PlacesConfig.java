package com.idraak.smartbite.v2.area;

import com.idraak.smartbite.v2.config.V2Properties;
import com.idraak.smartbite.v2.store.DocumentStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;

/** Active when PLACES_PROVIDER is google or fake; with "disabled" the no-Places grounding is used. */
@Configuration
@ConditionalOnExpression("'${smartbite.v2.places.provider:disabled}' != 'disabled'")
public class PlacesConfig {

    @Bean
    public PlacesClient placesClient(V2Properties props, ObjectMapper mapper) {
        V2Properties.Places p = props.places();
        if ("fake".equalsIgnoreCase(p.provider())) return new FakePlacesClient();
        if (!"google".equalsIgnoreCase(p.provider())) {
            throw new IllegalStateException("PLACES_PROVIDER must be google, fake or disabled");
        }
        if (p.apiKey() == null || p.apiKey().isBlank()) {
            throw new IllegalStateException("PLACES_PROVIDER=google needs GOOGLE_PLACES_API_KEY");
        }
        return new GooglePlacesClient(p.baseUrl(), p.apiKey(), p.connectTimeout(), p.readTimeout(), mapper);
    }

    @Bean
    public AreaGrounding placesAreaGrounding(PlacesClient client, DocumentStore store, V2Properties props,
                                             MeterRegistry meters, Clock clock) {
        V2Properties.Places p = props.places();
        return new PlacesAreaGrounding(client, new PlacesCache(store, p.cacheTtl(), clock), new PlacesBudget(store, p, clock),
                p.includedTypes(), p.verifyAlternatives(), p.stageBudget(), meters, clock);
    }

    @Bean
    public PlacesCacheCleanup placesCacheCleanup(DocumentStore store, Clock clock, TaskScheduler scheduler) {
        PlacesCacheCleanup cleanup = new PlacesCacheCleanup(store, clock);
        scheduler.scheduleWithFixedDelay(cleanup::run, java.time.Instant.now().plus(Duration.ofMinutes(1)), Duration.ofHours(6));
        return cleanup;
    }
}
