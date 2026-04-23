# Driver Tracker — Project Specification

## Overview

A real-time driver tracking demo that simulates 10,000 drivers moving
continuously within a city area. A browser-based map UI renders driver
positions live, updating every second within the visible viewport.
The project demonstrates S2 geo indexing, Redis spatial queries, and
WebSocket push architecture.

---

## Feature Description

### Simulation

- 10,000 drivers initialized at random positions within a bounding box
  (Paris area: lat 48.80–48.90, lng 2.25–2.45)
- Each driver moves continuously using a velocity-based random walk —
  a fixed velocity vector with small random perturbation each tick
- Drivers bounce off the bounding box boundaries (velocity reverses
  on boundary hit)
- Movement speed: ~50 meters per tick (0.0005 degrees/tick)
- Simulation tick: every 1 second

### Map UI

- Real OpenStreetMap tile map rendered via Leaflet.js
- Zoomable (scroll wheel) and pannable (click-drag)
- At zoom level < 12: no drivers rendered (too many to show)
- At zoom level >= 12: all drivers in the current viewport rendered
  as markers
- Markers update position smoothly every 1 second
- Markers appear when drivers enter the viewport, disappear when
  drivers leave
- Driver count displayed in a corner overlay

### Search

- Initial implementation: viewport bounding box (rectangular)
- Future: circle radius search, polygon shape search drawn by user
  on the map

---

## Architecture

### Update Frequency — Two-Tier

**Fast loop (every 1 second):**
- Push lat/lng positions for all known drivers in viewport
- Single Redis pipeline call — all driver positions fetched in one
  round trip
- Frontend clips to current map bounds — removes markers outside
  viewport without waiting for slow loop

**Slow loop (every 10 seconds):**
- Query Redis for current driver set in viewport
- Diff against previously known set → compute entered/left sets
- Push membership changes to frontend
- Frontend adds markers for entered drivers, removes for left drivers

### Viewport Subscription Model

Each WebSocket connection holds a `ClientSession`:

```
sessionId       WebSocket connection identifier
viewport        current map bounds (minLat, maxLat, minLng, maxLng)
knownDriverIds  Set<Integer> — drivers known to this client
zoom            current zoom level
positionFuture  scheduled task handle for fast loop
membershipFuture scheduled task handle for slow loop
```

On viewport change (pan/zoom):
- `knownDriverIds` is reset to empty
- Tasks are cancelled and rescheduled for new viewport
- Next slow loop tick populates `knownDriverIds` for new viewport

On disconnect:
- Both scheduled tasks are cancelled
- Session is removed from active sessions map

---

## Technology Stack

### Backend

- **Java 21** with Spring Boot 3.x
- **Virtual threads** (`spring.threads.virtual.enabled=true`) —
  one virtual thread per scheduled connection task; cheap blocking
  during sleep intervals between ticks
- **Spring WebSocket** with STOMP — bidirectional communication
  between browser and server
- **Spring Scheduling** (`@Scheduled`) — global simulation tick,
  per-connection tasks via `ScheduledExecutorService` with virtual
  thread factory
- **Jedis** — Redis client with pipeline support

### Geo Indexing — S2 Library

- **s2-geometry-library-java** — S2 cell ID computation and region
  covering
- Cell level: **20** (approximately 1m² per cell, fits within 52-bit
  double mantissa — safe for Redis sorted set score)
- lat/lng → S2 cell ID computed server-side during simulation
  (in production, would be computed client-side on the device)
- Viewport covering: `S2RegionCoverer` with `setMaxCells(8)`,
  `setMinLevel(8)`, `setMaxLevel(16)`

### Redis Data Model

**Driver position — Hash:**

```
Key:    driver:{id}
Fields: lat (double), lng (double)
```

Updated every simulation tick via pipeline.

**Spatial index — Sorted Set:**

```
Key:    drivers:index
Member: "{driverId}"  (string)
Score:  S2 cell ID at level 20 (double — safe at level 20 precision)
```

- `ZADD drivers:index <cellId> "<driverId>"` — insert or update
  (default ZADD updates score if member exists → exactly one entry
  per driver guaranteed)
- `ZRANGEBYSCORE drivers:index <minCellId> <maxCellId>` — range
  scan for viewport query

**Viewport query flow:**

```
1. convert viewport bounds → S2LatLngRect
2. S2RegionCoverer.getCovering() → list of S2CellUnion ranges
3. for each range:
     ZRANGEBYSCORE drivers:index rangeMin rangeMax
4. collect all driver IDs
5. pipeline HMGET driver:{id} lat lng for all IDs
6. return positions
```

### Frontend

- **Leaflet.js** — zoomable, pannable map with OpenStreetMap tiles
- **SockJS + STOMP.js** — WebSocket client with fallback
- No build tool — plain HTML + JS files served as static resources
  by Spring Boot
- **Marker management:**
  - `markers` object: `{ driverId → Leaflet marker }`
  - Fast loop: update `setLatLng` for existing markers
  - Slow loop entered: create new markers
  - Slow loop left: `map.removeLayer` + delete from `markers`
  - Frontend clips: on each position update, remove markers outside
    current `map.getBounds()`

### WebSocket Message Protocol

**Browser → Server:**

```json
{
  "type": "viewport",
  "minLat": 48.83,
  "maxLat": 48.87,
  "minLng": 2.30,
  "maxLng": 2.40,
  "zoom": 14
}
```

**Server → Browser (fast, every 1s):**

```json
{
  "type": "positions",
  "drivers": [
    { "id": 1, "lat": 48.856, "lng": 2.352 },
    { "id": 2, "lat": 48.861, "lng": 2.341 }
  ]
}
```

**Server → Browser (slow, every 10s):**

```json
{
  "type": "membership",
  "entered": [
    { "id": 6, "lat": 48.855, "lng": 2.338 }
  ],
  "left": [5, 9]
}
```

---

## Project Structure

```
services/driver-tracker/
  src/main/java/
    simulation/
      Driver.java                 velocity-based driver model
      DriverSimulator.java        @Scheduled simulation tick
    geo/
      S2Service.java              cell ID computation, region covering
    redis/
      DriverRepository.java       HSET, ZADD, pipeline HMGET, ZRANGEBYSCORE
    session/
      ClientSession.java          viewport, knownDriverIds, task handles
      SessionManager.java         ConcurrentHashMap of active sessions
    push/
      PositionPushService.java    fast loop — pipeline fetch + push
      MembershipPushService.java  slow loop — diff + push
    api/
      WebSocketController.java    STOMP message handlers
    config/
      WebSocketConfig.java        STOMP endpoint config
      RedisConfig.java            Jedis connection pool
      ThreadConfig.java           virtual thread executor
    model/
      DriverPosition.java
      ViewportMessage.java
      PositionUpdate.java
      MembershipUpdate.java
  src/main/resources/
    static/
      index.html                  map UI
      app.js                      Leaflet + STOMP client
    application.yml
```

---

## Key Design Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Geo index | S2 level 20 | uniform cell size globally, fits double precision |
| Redis structure | Sorted set (score=cellId, member=driverId) | single entry per driver, ZADD updates in place |
| Position storage | Hash per driver | field-level updates (lat/lng only), no full rewrite |
| Thread model | Virtual threads (Java 21) | cheap blocking between ticks, scales to many connections |
| Update split | 1s positions + 10s membership | reduces Redis load, smooth animation |
| Frontend clipping | client-side bounds check | handles staleness between slow loop ticks |
| Map library | Leaflet.js + OpenStreetMap | free, no API key, full-featured |
| WebSocket protocol | STOMP over SockJS | Spring Boot native support, fallback for non-WS |
| Redis client | Jedis with pipeline | pipeline batches N HMGET into 1 round trip |

---

## Performance Characteristics

### Memory (single host, 10k drivers)

```
Hash per driver:        ~150 bytes × 10k = 1.5MB
Sorted set entries:     ~100 bytes × 10k = 1.0MB
ClientSession per conn: ~800 bytes (200 driver IDs × 4 bytes)
Total Redis:            ~2.5MB
```

### Redis throughput (simulation tick, 10k drivers)

```
10k HSET + 10k ZADD = 20k commands/tick
batched via pipeline  = ~2 round trips
Redis capacity:        ~500k commands/second → trivial load
```

### Redis throughput (push, per connection)

```
fast loop (1s):   1 pipeline round trip (N HMGET batched)
slow loop (10s):  1-8 ZRANGEBYSCORE calls per viewport covering
```

---

## Build and Run

```bash
# start Redis (Docker)
docker run -d -p 6379:6379 redis:7

# run backend
./mvnw spring-boot:run \
  -pl services/driver-tracker \
  -Dspring-boot.run.profiles=dev

# open browser
open http://localhost:8080
```

---

## Future Enhancements

- Circle radius search drawn on map by user
- Polygon shape search (Leaflet.draw plugin)
- Zoom-based clustering (show count bubbles when zoomed out)
- Driver detail popup on marker click
- Simulation speed control (UI slider)
- Multiple city bounding boxes selectable
- Metrics overlay (Redis ops/sec, active connections, drivers/viewport)
