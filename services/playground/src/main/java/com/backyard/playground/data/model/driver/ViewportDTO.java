package com.backyard.playground.data.model.driver;

public class ViewportDTO {

    private double minLat;
    private double maxLat;
    private double minLng;
    private double maxLng;
    private int zoom;

    public double getMinLat() { return minLat; }
    public void setMinLat(double minLat) { this.minLat = minLat; }
    public double getMaxLat() { return maxLat; }
    public void setMaxLat(double maxLat) { this.maxLat = maxLat; }
    public double getMinLng() { return minLng; }
    public void setMinLng(double minLng) { this.minLng = minLng; }
    public double getMaxLng() { return maxLng; }
    public void setMaxLng(double maxLng) { this.maxLng = maxLng; }
    public int getZoom() { return zoom; }
    public void setZoom(int zoom) { this.zoom = zoom; }
}
