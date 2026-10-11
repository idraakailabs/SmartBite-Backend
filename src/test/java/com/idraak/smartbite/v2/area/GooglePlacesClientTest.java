package com.idraak.smartbite.v2.area;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GooglePlacesClientTest {

    WireMockServer google;
    GooglePlacesClient client;

    @BeforeEach
    void start() {
        google = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));
        google.start();
        client = new GooglePlacesClient(google.baseUrl() + "/v1", "test-places-key", Duration.ofSeconds(2), Duration.ofSeconds(1),
                JsonMapper.builder().build());
    }

    @AfterEach
    void stop() {
        google.stop();
    }

    @Test
    void nearbyUsesTheMinimalFieldMaskAndCircleRestriction() {
        google.stubFor(post("/v1/places:searchNearby").willReturn(okJson("""
                {"places":[{"id":"p1","location":{"latitude":31.47,"longitude":74.27}},{"id":"p2"}]}""")));
        List<PlacesClient.Place> result = client.nearby(31.4697, 74.2728, 3.7, List.of("restaurant"));
        assertThat(result).containsExactly(new PlacesClient.Place("p1", 31.47, 74.27));
        google.verify(postRequestedFor(urlEqualTo("/v1/places:searchNearby"))
                .withHeader("X-Goog-Api-Key", equalTo("test-places-key"))
                .withHeader("X-Goog-FieldMask", equalTo("places.id,places.location"))
                .withRequestBody(matchingJsonPath("$.includedTypes[0]", equalTo("restaurant")))
                .withRequestBody(matchingJsonPath("$.maxResultCount", equalTo("20")))
                .withRequestBody(matchingJsonPath("$.rankPreference", equalTo("POPULARITY")))
                .withRequestBody(matchingJsonPath("$.locationRestriction.circle.radius", equalTo("3700.0"))));
    }

    @Test
    void textSearchRequestsIdsOnly() {
        google.stubFor(post("/v1/places:searchText").willReturn(okJson("{\"places\":[{\"id\":\"p1\"},{\"id\":\"p9\"}]}")));
        assertThat(client.textSearchIds("Mutton Karahi", 31.4697, 74.2728, 3)).containsExactly("p1", "p9");
        google.verify(postRequestedFor(urlEqualTo("/v1/places:searchText"))
                .withHeader("X-Goog-FieldMask", equalTo("places.id"))
                .withRequestBody(matchingJsonPath("$.textQuery", equalTo("Mutton Karahi")))
                .withRequestBody(matchingJsonPath("$.locationBias.circle.radius", equalTo("3000.0"))));
    }

    @Test
    void emptyResultIsAnEmptyList() {
        google.stubFor(post("/v1/places:searchText").willReturn(okJson("{}")));
        assertThat(client.textSearchIds("x", 1, 1, 1)).isEmpty();
    }

    @Test
    void errorsMapToKinds() {
        google.stubFor(post("/v1/places:searchNearby").willReturn(aResponse().withStatus(429)));
        google.stubFor(post("/v1/places:searchText").willReturn(aResponse().withStatus(403)));
        assertThatThrownBy(() -> client.nearby(1, 1, 1, List.of("restaurant")))
                .isInstanceOfSatisfying(PlacesClient.PlacesException.class, e -> assertThat(e.kind()).isEqualTo(PlacesClient.PlacesException.Kind.QUOTA));
        assertThatThrownBy(() -> client.textSearchIds("x", 1, 1, 1))
                .isInstanceOfSatisfying(PlacesClient.PlacesException.class, e -> assertThat(e.kind()).isEqualTo(PlacesClient.PlacesException.Kind.ERROR));
    }

    @Test
    void timeoutIsReported() {
        google.stubFor(post("/v1/places:searchText").willReturn(okJson("{}").withFixedDelay(2_000)));
        assertThatThrownBy(() -> client.textSearchIds("x", 1, 1, 1))
                .isInstanceOfSatisfying(PlacesClient.PlacesException.class, e -> assertThat(e.kind()).isEqualTo(PlacesClient.PlacesException.Kind.TIMEOUT));
    }
}
