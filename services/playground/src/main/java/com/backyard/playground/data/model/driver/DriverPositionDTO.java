package com.backyard.playground.data.model.driver;

import java.util.UUID;

public class DriverPositionDTO {

    private UUID id;
    private double lat;
    private double lng;

    public DriverPositionDTO() {}

    public DriverPositionDTO(UUID id, double lat, double lng) {
        this.id = id;
        this.lat = lat;
        this.lng = lng;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public double getLat() { return lat; }
    public void setLat(double lat) { this.lat = lat; }
    public double getLng() { return lng; }
    public void setLng(double lng) { this.lng = lng; }
}
