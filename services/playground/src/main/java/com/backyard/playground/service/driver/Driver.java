package com.backyard.playground.service.driver;

import java.util.Random;
import java.util.UUID;

public class Driver {

    static final double MIN_LAT = 37.20;
    static final double MAX_LAT = 37.45;
    static final double MIN_LNG = -122.05;
    static final double MAX_LNG = -121.75;

    private static final double STEP = 0.0005;
    private static final double PERTURB = 0.0001;

    private final UUID id;
    private double lat;
    private double lng;
    private double velLat;
    private double velLng;

    public Driver(UUID id, Random rng) {
        this.id = id;
        this.lat = MIN_LAT + rng.nextDouble() * (MAX_LAT - MIN_LAT);
        this.lng = MIN_LNG + rng.nextDouble() * (MAX_LNG - MIN_LNG);
        double angle = rng.nextDouble() * 2 * Math.PI;
        this.velLat = Math.sin(angle) * STEP;
        this.velLng = Math.cos(angle) * STEP;
    }

    /** Restores a driver from persisted position (e.g. Redis on leader takeover). */
    public Driver(UUID id, double lat, double lng, Random rng) {
        this.id = id;
        this.lat = lat;
        this.lng = lng;
        double angle = rng.nextDouble() * 2 * Math.PI;
        this.velLat = Math.sin(angle) * STEP;
        this.velLng = Math.cos(angle) * STEP;
    }

    public void move(Random rng) {
        velLat += (rng.nextDouble() - 0.5) * PERTURB;
        velLng += (rng.nextDouble() - 0.5) * PERTURB;
        double speed = Math.sqrt(velLat * velLat + velLng * velLng);
        if (speed > STEP * 2) {
            velLat = velLat / speed * STEP * 2;
            velLng = velLng / speed * STEP * 2;
        }
        lat += velLat;
        lng += velLng;
        if (lat < MIN_LAT || lat > MAX_LAT) {
            velLat = -velLat;
            lat = Math.max(MIN_LAT, Math.min(MAX_LAT, lat));
        }
        if (lng < MIN_LNG || lng > MAX_LNG) {
            velLng = -velLng;
            lng = Math.max(MIN_LNG, Math.min(MAX_LNG, lng));
        }
    }

    public UUID getId() { return id; }
    public double getLat() { return lat; }
    public double getLng() { return lng; }
}
