package com.idraak.smartbite.v2.area;

/** Geohash cells and great-circle distance for the area pipeline (README §7). */
public final class Geo {

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";
    private static final double EARTH_RADIUS_KM = 6371.0088;

    private Geo() {
    }

    public record Bounds(double minLat, double maxLat, double minLng, double maxLng) {
        public double centerLat() {
            return (minLat + maxLat) / 2;
        }

        public double centerLng() {
            return (minLng + maxLng) / 2;
        }

        public double halfDiagonalKm() {
            return distanceKm(minLat, minLng, maxLat, maxLng) / 2;
        }
    }

    /** A cache cell: geohash of the point plus the radius bucket, with the circle to query so it covers any point in the cell. */
    public record Cell(String key, double centerLat, double centerLng, int bucketKm, double queryRadiusKm) {}

    public static String geohash(double lat, double lng, int precision) {
        double[] latR = {-90, 90};
        double[] lngR = {-180, 180};
        StringBuilder sb = new StringBuilder();
        boolean even = true;
        int bit = 0;
        int ch = 0;
        while (sb.length() < precision) {
            double[] r = even ? lngR : latR;
            double v = even ? lng : lat;
            double mid = (r[0] + r[1]) / 2;
            if (v >= mid) {
                ch |= 1 << (4 - bit);
                r[0] = mid;
            } else {
                r[1] = mid;
            }
            even = !even;
            if (++bit == 5) {
                sb.append(BASE32.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }
        return sb.toString();
    }

    public static Bounds bounds(String geohash) {
        double[] latR = {-90, 90};
        double[] lngR = {-180, 180};
        boolean even = true;
        for (char c : geohash.toCharArray()) {
            int cd = BASE32.indexOf(c);
            for (int mask = 16; mask > 0; mask >>= 1) {
                double[] r = even ? lngR : latR;
                double mid = (r[0] + r[1]) / 2;
                if ((cd & mask) != 0) r[0] = mid;
                else r[1] = mid;
                even = !even;
            }
        }
        return new Bounds(latR[0], latR[1], lngR[0], lngR[1]);
    }

    /** Radius buckets match the app presets: 1, 3, 5, 10 km. */
    public static int bucketKm(double radiusKm) {
        if (radiusKm <= 1) return 1;
        if (radiusKm <= 3) return 3;
        if (radiusKm <= 5) return 5;
        return 10;
    }

    public static Cell cell(double lat, double lng, double radiusKm) {
        int bucket = bucketKm(radiusKm);
        int precision = bucket <= 5 ? 6 : 5;
        String hash = geohash(lat, lng, precision);
        Bounds b = bounds(hash);
        double query = Math.min(50, bucket + b.halfDiagonalKm());
        return new Cell(hash + "_" + bucket, b.centerLat(), b.centerLng(), bucket, query);
    }

    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
