package com.idraak.smartbite.v2.area;

import java.util.List;

/** Google Places API (New), reduced to what the backend may use and cache: place ids and coordinates. */
public interface PlacesClient {

    record Place(String id, double lat, double lng) {}

    /** Nearby Search (Pro SKU) with field mask {@code places.id,places.location}. */
    List<Place> nearby(double lat, double lng, double radiusKm, List<String> includedTypes);

    /** Text Search with field mask {@code places.id} only (Essentials IDs Only SKU) and a circle location bias. */
    List<String> textSearchIds(String query, double lat, double lng, double radiusKm);

    class PlacesException extends RuntimeException {
        public enum Kind { QUOTA, ERROR, TIMEOUT }

        private final Kind kind;

        public PlacesException(Kind kind, String message, Throwable cause) {
            super(message, cause, false, false);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }
}
