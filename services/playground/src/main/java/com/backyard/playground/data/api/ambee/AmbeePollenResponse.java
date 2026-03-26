package com.backyard.playground.data.api.ambee;

import java.util.List;

public record AmbeePollenResponse(
        double lat,
        double lng,
        String message,
        List<PollenData> data) {
}
