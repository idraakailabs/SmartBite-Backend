package com.idraak.smartbite.v2.area;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Places API (New) over HTTPS with the key in {@code X-Goog-Api-Key} and a minimal {@code X-Goog-FieldMask}.
 * Field masks verified 2026-10-11: {@code places.id,places.location} triggers Nearby Search Pro (there's no lower
 * Nearby tier); {@code places.id} alone is Text Search Essentials (IDs Only).
 */
public class GooglePlacesClient implements PlacesClient {

    static final String NEARBY_FIELD_MASK = "places.id,places.location";
    static final String TEXT_FIELD_MASK = "places.id";

    private final String baseUrl;
    private final String apiKey;
    private final Duration readTimeout;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public GooglePlacesClient(String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout, ObjectMapper mapper) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.readTimeout = readTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).version(HttpClient.Version.HTTP_1_1).build();
        this.mapper = mapper;
    }

    @Override
    public List<Place> nearby(double lat, double lng, double radiusKm, List<String> includedTypes) {
        ObjectNode body = mapper.createObjectNode();
        var types = body.putArray("includedTypes");
        includedTypes.forEach(types::add);
        body.put("maxResultCount", 20);
        body.put("rankPreference", "POPULARITY");
        circle(body.putObject("locationRestriction"), lat, lng, radiusKm);
        JsonNode res = post("/places:searchNearby", NEARBY_FIELD_MASK, body);
        List<Place> out = new ArrayList<>();
        for (JsonNode p : res.path("places")) {
            JsonNode loc = p.path("location");
            if (p.path("id").isString() && loc.path("latitude").isNumber() && loc.path("longitude").isNumber()) {
                out.add(new Place(p.path("id").asString(), loc.path("latitude").asDouble(), loc.path("longitude").asDouble()));
            }
        }
        return out;
    }

    @Override
    public List<String> textSearchIds(String query, double lat, double lng, double radiusKm) {
        ObjectNode body = mapper.createObjectNode();
        body.put("textQuery", query);
        body.put("pageSize", 20);
        circle(body.putObject("locationBias"), lat, lng, radiusKm);
        JsonNode res = post("/places:searchText", TEXT_FIELD_MASK, body);
        List<String> ids = new ArrayList<>();
        for (JsonNode p : res.path("places")) {
            if (p.path("id").isString()) ids.add(p.path("id").asString());
        }
        return ids;
    }

    private static void circle(ObjectNode parent, double lat, double lng, double radiusKm) {
        ObjectNode circle = parent.putObject("circle");
        circle.putObject("center").put("latitude", lat).put("longitude", lng);
        circle.put("radius", Math.min(50_000.0, radiusKm * 1000.0));
    }

    private JsonNode post(String path, String fieldMask, ObjectNode body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(readTimeout)
                .header("Content-Type", "application/json")
                .header("X-Goog-Api-Key", apiKey)
                .header("X-Goog-FieldMask", fieldMask)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new PlacesException(PlacesException.Kind.TIMEOUT, "places timeout", e);
        } catch (IOException e) {
            throw new PlacesException(PlacesException.Kind.ERROR, "places I/O error", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlacesException(PlacesException.Kind.ERROR, "places interrupted", e);
        }
        int status = response.statusCode();
        if (status == 429) throw new PlacesException(PlacesException.Kind.QUOTA, "places quota exceeded", null);
        if (status != 200) throw new PlacesException(PlacesException.Kind.ERROR, "places status " + status, null);
        try {
            return mapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw new PlacesException(PlacesException.Kind.ERROR, "places unreadable response", e);
        }
    }
}
