package com.idraak.smartbite.v2.entitlement;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * RevenueCat REST API v2 (verified 2026-10-11): {@code GET /projects/{p}/customers/{id}} returns
 * {@code active_entitlements.items[].entitlement_id} (the internal id), so the configured lookup key
 * ({@code smartbite_pro}) is resolved once through {@code GET /projects/{p}/entitlements}. A 404 customer is not
 * Premium. {@code DELETE /projects/{p}/customers/{id}} queues the deletion. API v1's GET is "get or create", so it
 * isn't used.
 */
public class RevenueCatClient implements BillingProvider {

    private final RestClient http;
    private final String projectId;
    private final String lookupKey;
    private volatile String entitlementId;

    public RevenueCatClient(String baseUrl, String secretKey, String projectId, String lookupKey,
                            Duration connectTimeout, Duration readTimeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(readTimeout);
        this.http = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + secretKey)
                .build();
        this.projectId = projectId;
        this.lookupKey = lookupKey;
    }

    String entitlementId() {
        String id = entitlementId;
        if (id != null) return id;
        try {
            JsonNode body = http.get().uri("/projects/{p}/entitlements?limit=100", projectId).retrieve().body(JsonNode.class);
            for (JsonNode item : body.path("items")) {
                if (lookupKey.equals(item.path("lookup_key").asString(""))) {
                    entitlementId = item.path("id").asString();
                    return entitlementId;
                }
            }
        } catch (RestClientException e) {
            throw new EntitlementUnavailableException("entitlement lookup failed", e);
        }
        throw new EntitlementUnavailableException("entitlement lookup key not found in RevenueCat project", null);
    }

    @Override
    public boolean isPremium(String uid) {
        String wanted = entitlementId();
        try {
            JsonNode body = http.get().uri("/projects/{p}/customers/{id}", projectId, uid)
                    .retrieve()
                    .onStatus(s -> s.value() == 404, (req, res) -> { })
                    .body(JsonNode.class);
            if (body == null || body.path("active_entitlements").isMissingNode()) return false;
            for (JsonNode item : body.path("active_entitlements").path("items")) {
                if (wanted.equals(item.path("entitlement_id").asString(""))) return true;
            }
            return false;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return false;
            throw new EntitlementUnavailableException("customer read failed: " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new EntitlementUnavailableException("customer read failed", e);
        }
    }

    @Override
    public Outcome deleteCustomer(String uid) {
        try {
            HttpStatusCode status = http.delete().uri("/projects/{p}/customers/{id}", projectId, uid)
                    .retrieve()
                    .onStatus(s -> s.value() == 404, (req, res) -> { })
                    .toBodilessEntity()
                    .getStatusCode();
            return status.value() == 404 ? Outcome.NOT_FOUND : Outcome.DELETION_QUEUED;
        } catch (RestClientException e) {
            throw new EntitlementUnavailableException("customer delete failed", e);
        }
    }
}
