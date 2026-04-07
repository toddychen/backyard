package com.backyard.playground.service;

import com.backyard.playground.cache.CacheConfig;
import com.backyard.playground.client.rest.AmbeePollenClient;
import com.backyard.playground.data.api.ambee.AmbeePollenResponse;
import com.backyard.playground.data.api.ambee.PollenData;
import com.backyard.playground.data.model.geo.SupportedCityDTO;
import com.backyard.playground.data.model.weather.pollen.PollenCategoryDTO;
import com.backyard.playground.data.model.weather.pollen.PollenReportDTO;
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
    public PollenReportDTO getPollenByCity(String city) {
        var supported = SupportedCityDTO.fromSlug(city);
        if (supported.isEmpty()) {
            throw new NotFoundException("city not supported: " + city);
        }
        var c = supported.get();
        log.info("fetching pollen for city='{}'", c.getSlug());
        var response = ambeePollenClient.getLatestPollen(c.getLatitude(), c.getLongitude(), true);
        return toPollenReport(response);
    }

    private PollenReportDTO toPollenReport(AmbeePollenResponse response) {
        if (response.data() == null || response.data().isEmpty()) {
            throw new ServiceUnavailableException("no pollen data returned from upstream");
        }
        PollenData d = response.data().get(0);
        return new PollenReportDTO(
                response.lat(),
                response.lng(),
                d.updatedAt(),
                new PollenCategoryDTO(
                        d.count().grassPollen(), d.risk().grassPollen(), d.species().grass()),
                new PollenCategoryDTO(
                        d.count().treePollen(), d.risk().treePollen(), d.species().tree()),
                new PollenCategoryDTO(
                        d.count().weedPollen(), d.risk().weedPollen(), d.species().weed()));
    }
}
