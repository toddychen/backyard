package com.backyard.playground.service.driver;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.backyard.playground.data.model.driver.ViewportDTO;
import com.google.common.geometry.S2CellId;
import com.google.common.geometry.S2CellUnion;
import com.google.common.geometry.S2LatLng;
import com.google.common.geometry.S2LatLngRect;
import com.google.common.geometry.S2RegionCoverer;

@Service
@Profile("!home")
public class S2Service {

    private static final int CELL_LEVEL = 20;

    public long cellId(double lat, double lng) {
        return S2CellId.fromLatLng(S2LatLng.fromDegrees(lat, lng)).parent(CELL_LEVEL).id();
    }

    /** Returns [rangeMin, rangeMax] pairs for a ZRANGEBYSCORE viewport query. */
    public List<long[]> coverViewport(ViewportDTO vp) {
        S2LatLng lo = S2LatLng.fromDegrees(vp.getMinLat(), vp.getMinLng());
        S2LatLng hi = S2LatLng.fromDegrees(vp.getMaxLat(), vp.getMaxLng());
        S2LatLngRect rect = S2LatLngRect.fromPointPair(lo, hi);

        S2RegionCoverer coverer = S2RegionCoverer.builder()
                .setMaxCells(8)
                .setMinLevel(8)
                .setMaxLevel(16)
                .build();
        S2CellUnion covering = coverer.getCovering(rect);

        List<long[]> ranges = new ArrayList<>();
        for (S2CellId cell : covering.cellIds()) {
            ranges.add(new long[] { cell.rangeMin().id(), cell.rangeMax().id() });
        }
        return ranges;
    }
}
