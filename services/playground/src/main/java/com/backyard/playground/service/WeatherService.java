package com.backyard.playground.service;

import com.backyard.playground.cache.CacheConfig;
import com.backyard.playground.client.AmbeePollenClient;
import com.backyard.playground.data.api.ambee.AmbeePollenResponse;
import com.backyard.playground.data.api.ambee.PollenData;
import com.backyard.playground.data.geo.SupportedCity;
import com.backyard.playground.data.weather.pollen.PollenCategory;
import com.backyard.playground.data.weather.pollen.PollenReport;
import com.backyard.playground.exception.NotFoundException;
import com.backyard.playground.exception.ServiceUnavailableException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class WeatherService {
    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private final AmbeePollenClient ambeePollenClient;

    public WeatherService(AmbeePollenClient ambeePollenClient) {
        this.ambeePollenClient = ambeePollenClient;
    }

    @Cacheable(value = CacheConfig.CACHE_POLLEN, key = "#city")
    public PollenReport getPollenByCity(String city) {
        var supported = SupportedCity.fromSlug(city);
        if (supported.isEmpty()) {
            throw new NotFoundException("city not supported: " + city);
        }
        var c = supported.get();
        log.info("fetching pollen for city='{}'", c.getSlug());
        var response = ambeePollenClient.getLatestPollen(c.getLatitude(), c.getLongitude(), true);
        return toPollenReport(response);
    }

    private PollenReport toPollenReport(AmbeePollenResponse response) {
        if (response.data() == null || response.data().isEmpty()) {
            throw new ServiceUnavailableException("no pollen data returned from upstream");
        }
        PollenData d = response.data().get(0);
        return new PollenReport(
                response.lat(),
                response.lng(),
                d.updatedAt(),
                new PollenCategory(
                        d.count().grassPollen(), d.risk().grassPollen(), d.species().grass()),
                new PollenCategory(
                        d.count().treePollen(), d.risk().treePollen(), d.species().tree()),
                new PollenCategory(
                        d.count().weedPollen(), d.risk().weedPollen(), d.species().weed()));
    }
}
