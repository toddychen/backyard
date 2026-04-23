package com.backyard.playground.service.driver;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.backyard.playground.client.rest.OsrmClient;
import com.backyard.playground.data.api.osrm.OsrmRouteResponse;
import com.backyard.playground.data.model.driver.RouteDTO;
import com.backyard.playground.data.model.driver.RouteResponseDTO;

@Service
@Profile("!home")
public class RouteService {

    private static final int MAX_ALTERNATIVES = 3;
    private static final String OVERVIEW = "full";
    private static final String GEOMETRIES = "geojson";

    private final OsrmClient osrmClient;

    public RouteService(OsrmClient osrmClient) {
        this.osrmClient = osrmClient;
    }

    public RouteResponseDTO getRoutes(double fromLat, double fromLng, double toLat, double toLng) {
        String coords = fromLng + "," + fromLat + ";" + toLng + "," + toLat;
        OsrmRouteResponse response = osrmClient.route(coords, MAX_ALTERNATIVES, OVERVIEW, GEOMETRIES);
        if (!"Ok".equals(response.code()) || response.routes() == null) {
            return new RouteResponseDTO(List.of());
        }
        List<RouteDTO> routes = response.routes().stream()
                .map(r -> new RouteDTO(
                        r.duration() != null ? r.duration().intValue() : null,
                        r.distance() != null ? r.distance().intValue() : null,
                        flipCoordinates(r.geometry().coordinates())))
                .toList();
        return new RouteResponseDTO(routes);
    }

    // OSRM returns [lng, lat]; Leaflet expects [lat, lng]
    private List<List<Double>> flipCoordinates(List<List<Double>> coords) {
        return coords.stream()
                .map(c -> List.of(c.get(1), c.get(0)))
                .toList();
    }
}
