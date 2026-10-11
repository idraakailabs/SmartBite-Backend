package com.idraak.smartbite.v2.area;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GeoTest {

    @Test
    void geohashMatchesTheReferenceValue() {
        // Reference example from the geohash literature.
        assertThat(Geo.geohash(57.64911, 10.40744, 11)).isEqualTo("u4pruydqqvj");
    }

    @Test
    void boundsContainThePoint() {
        Geo.Bounds b = Geo.bounds(Geo.geohash(31.4697, 74.2728, 6));
        assertThat(31.4697).isBetween(b.minLat(), b.maxLat());
        assertThat(74.2728).isBetween(b.minLng(), b.maxLng());
        assertThat(b.halfDiagonalKm()).isBetween(0.5, 0.8);
    }

    @Test
    void cellQueryCircleCoversAnyPointInTheCellPlusTheRadius() {
        Geo.Cell c = Geo.cell(31.4697, 74.2728, 2.5);
        assertThat(c.bucketKm()).isEqualTo(3);
        assertThat(c.key()).endsWith("_3").hasSize(8);
        double centreToUser = Geo.distanceKm(c.centerLat(), c.centerLng(), 31.4697, 74.2728);
        assertThat(c.queryRadiusKm()).isGreaterThanOrEqualTo(centreToUser + 2.5);
        assertThat(Geo.cell(31.4697, 74.2728, 10).key()).endsWith("_10").hasSize(8);
    }

    @Test
    void distanceIsPlausible() {
        // Lahore (Mall Road) to Johar Town is roughly 9-11 km.
        assertThat(Geo.distanceKm(31.5600, 74.3290, 31.4697, 74.2728)).isCloseTo(11.3, within(1.5));
    }
}
