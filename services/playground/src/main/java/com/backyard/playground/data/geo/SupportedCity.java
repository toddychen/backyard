package com.backyard.playground.data.geo;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum SupportedCity {

    SAN_JOSE("san-jose", 37.3716914, -121.8619539);

    private final String slug;
    private final double latitude;
    private final double longitude;

    private static final Map<String, SupportedCity> SLUG_MAP = Stream.of(values())
            .collect(Collectors.toMap(c -> c.slug.toLowerCase(), c -> c));

    SupportedCity(String slug, double latitude, double longitude) {
        this.slug = slug;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String getSlug() {
        return slug;
    }
    public double getLatitude() {
        return latitude;
    }
    public double getLongitude() {
        return longitude;
    }

    public static Optional<SupportedCity> fromSlug(String slug) {
        return Optional.ofNullable(SLUG_MAP.get(slug.toLowerCase()));
    }
}
