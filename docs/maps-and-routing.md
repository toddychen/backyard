# Maps and Routing — Educational Reference

A bottom-up study of how mapping and routing works in production systems,
covering map tiles, GPS pipelines, traffic data, graph data structures, and
routing algorithms from Dijkstra through Contraction Hierarchies.

---

## 1. Map Tiles

### Raster vs Vector Tiles

**Raster tiles** are pre-rendered PNG/JPEG images. The server renders the map
at every zoom level and cuts it into 256×256 pixel tiles. Simple to serve,
but large storage footprint and fixed visual style — you cannot change fonts
or colors without re-rendering everything.

**Vector tiles** are protobuf-encoded geometry and metadata. The client
receives raw geometry (points, lines, polygons) and tags (name, road type,
speed limit) and renders them locally using WebGL. The same tile file can be
styled differently at render time. This is how Mapbox, Google Maps, and Apple
Maps work.

### How a Vector Tile Is Structured

A single tile covers a geographic bounding box at a given zoom level. Inside:

```
Tile
  Layer: "roads"
    Feature (LineString): [[x1,y1],[x2,y2],[x3,y3]]
      tags: { highway: "primary", name: "El Camino Real", oneway: true }
    Feature (LineString): ...
  Layer: "buildings"
    Feature (Polygon): [[...]]
      tags: { building: "yes", height: 12 }
  Layer: "labels"
    Feature (Point): [cx, cy]
      tags: { name: "Starbucks", type: "cafe" }
```

Coordinates inside a tile are integers in tile-local space (0–4096), not
geographic coordinates. All curves are approximated by straight line segments
— a curved highway is a polyline with many short segments. There are no true
curves in the protobuf format.

Road intersections are not separate objects. Two road LineStrings simply share
a coordinate point. The client detects intersections geometrically when
rendering. Road name labels are Point features with a reference to the road
segment they belong to, so the renderer can orient the label along the road.

### Tile Pricing and Self-Hosting

OpenStreetMap tiles are free but rate-limited for production use. Mapbox and
Google Maps charge per map load (~28,000 free loads/month on Mapbox). For a
consumer app at scale, tile costs become significant.

Self-hosting is possible using OpenStreetMap data + tools like Tegola or
Martin (vector tile servers) backed by PostGIS. Tile files are static and
CDN-friendly once generated.

---

## 2. GPS Data Ingestion Pipeline

### Overview

```
Driver app → GPS ping (lat, lng, timestamp, heading, speed)
    → Kafka topic: gps-raw
    → Map matching service (stateful, per-driver)
    → Kafka topic: gps-matched (snapped to road segment)
    → Traffic aggregation service
    → Live traffic store + historical traffic store
```

### Map Matching — Hidden Markov Model

Raw GPS pings have ~5–15m error. Snapping each ping naively to the nearest
road segment is wrong — the nearest segment geometrically may not be the one
the driver is actually on (parallel roads, overpasses, tunnels).

The correct approach models GPS as a Hidden Markov Model:

- **Hidden states**: the true road segment the driver is on
- **Observations**: the noisy GPS pings

**Emission probability** — how likely is GPS ping P given the driver is on
segment S:

```
P(ping | segment) = Gaussian(distance(ping, segment), σ=4.07m)
```

**Transition probability** — how likely is the driver to move from segment A
to segment B between two consecutive pings:

```
P(A → B) ∝ exp(-|gps_distance - road_distance| / β)
```

Where `gps_distance` is the straight-line distance between the two pings and
`road_distance` is the actual shortest road path from A to B. If those two
are similar, the transition is likely. A large difference suggests an
impossible or unlikely route.

Speed consistency and heading delta are also factored into transition
probability for robustness on mountain roads with many turns.

**Viterbi algorithm** finds the most likely sequence of segments given all
pings, running in O(T × S²) where T = number of pings and S = candidate
segments per ping (typically 5–10). The service is stateful per driver,
carrying forward the Viterbi trellis across ping batches.

---

## 3. Traffic Data: Collection and Storage

### Live Traffic

After map matching, each matched ping contributes a speed observation to its
road segment. A streaming aggregator (Flink or Kafka Streams) maintains a
rolling window (e.g., last 5 minutes) of observed speeds per segment:

```
segment_id → [speed1, speed2, ...] → median speed → current_travel_time
```

Stored in Redis as `segment:{id}:speed` with TTL. Routing engine reads these
during the customization phase.

### Historical Traffic

The same matched pings are written to a time-series store bucketed by:

```
(segment_id, day_of_week, hour_of_day) → p50_speed, p85_speed
```

Query pattern: "what is the typical speed on segment X on Tuesday at 8am?"
Used for departure-time routing and for traffic estimates on segments where
live data is absent.

### Time-Series Storage

A dedicated time-series database (InfluxDB, TimescaleDB, or Apache Druid)
is the natural fit for historical speed data. It supports:

- Efficient range queries: `SELECT speed FROM segments WHERE time BETWEEN ...`
- Downsampling: roll up raw per-minute data into hourly or daily aggregates
- Retention policies: drop raw data after 90 days, keep aggregates forever

Alternative: store historical buckets in a columnar store (Parquet on S3 +
Athena) for cheap long-term retention with acceptable query latency.

---

## 4. Routing Graph: Data Structures and Preparation

### OSM Data Model

OpenStreetMap represents the world as:

- **Nodes**: lat/lng points with a unique ID
- **Ways**: ordered sequences of node IDs representing roads, paths, buildings
- **Relations**: groupings of ways (e.g., a turn restriction, a route)

A single OSM way can represent a long road segment (a highway for miles with
many intermediate shape points). During graph construction, ways are split at
intersections to produce true graph edges.

### Graph Data Structures

**Adjacency list with edge offsets** is the standard in-memory representation:

```
nodes[]:
    id, lat, lng
    edge_offset   ← index into edges[] where this node's outgoing edges start

edges[]:
    target_node
    cost          ← travel time in seconds (or distance)
    forward_heading, backward_heading
    via_segments[]: list of shape points (for geometry)
```

To get all outgoing edges of node u:

```
start = nodes[u].edge_offset
end   = nodes[u+1].edge_offset
outgoing_edges = edges[start..end]
```

This gives O(1) edge access with no pointer chasing, cache-friendly layout.

### Turn Restrictions and Turn Costs

Turn costs (left turn penalty, U-turn prohibition) are path-dependent — the
cost of traversing an edge depends on which edge you came from. This breaks
standard node-based Dijkstra.

Fix: **node expansion**. Replace every node v with one copy per incoming edge:

```
Before: a → v → c    (turn cost a→v→c = 0)
        b → v → c    (turn cost b→v→c = +30s left turn penalty)

After:  a → v_from_a → c    (edge cost includes 0s turn)
        b → v_from_b → c    (edge cost includes 30s turn)
```

Turn costs become plain edge costs. All routing algorithms work normally on
the expanded graph. Graph size increases ~2.5× but the structure is standard.

### Data Preparation Summary

```
OSM PBF file
    → parse ways, split at intersections → raw graph (nodes + edges)
    → apply speed limits / road type speeds → edge costs
    → apply turn restrictions → node expansion
    → apply historical traffic → time-dependent edge costs
    → CH preprocessing → augmented graph with shortcuts + ranks
```

---

## 5. Routing Algorithms

### 5.1 Dijkstra

Classic single-source shortest path. Uses a min-priority queue ordered by
`g(u)` — the actual cost from source to node u.

```
g(source) = 0, g(all others) = ∞
push source into priority queue

while queue not empty:
    u = pop min g(u)
    if u == destination: done
    for each neighbor v of u:
        if g(u) + cost(u,v) < g(v):
            g(v) = g(u) + cost(u,v)
            push v into queue
```

The priority queue may hold multiple entries for the same node (lazy
deletion). When popping node u, if `g(u)` is already better than the popped
value, discard — it's stale.

**Complexity**: O((V + E) log V) time, O(V + E) space.
**Query time on country-scale road network**: seconds.

### 5.2 Bidirectional Dijkstra

Run forward Dijkstra from source and backward Dijkstra from destination
simultaneously. The backward search traverses reversed edges.

Track `μ` — the best path found so far through any node settled by both
searches:

```
whenever node u is settled by both forward and backward:
    μ = min(μ, g_f(u) + g_b(u))
```

**Termination**:

```
stop when: g_f(top_f) + g_b(top_b) >= μ
```

Any remaining unsettled path must cost at least `g_f(top_f) + g_b(top_b)`,
which is already >= μ. ~2× speedup over unidirectional Dijkstra.

### 5.3 A*

Adds a heuristic `h(u)` — a lower bound on the remaining cost from u to
destination. Uses `f(u) = g(u) + h(u)` as the priority queue key.

The heuristic guides the search toward the destination, pruning unpromising
directions.

**Admissibility requirement**: `h(u) <= actual_cost(u, destination)` always.
Violation causes incorrect results.

**Consistency (monotone)**: `h(u) <= cost(u,v) + h(v)` for every edge (u,v).
Consistency implies admissibility and ensures nodes are settled optimally
without re-expansion.

Common heuristic for road networks: `straight_line(u, dest) / max_speed`.
Weak because roads curve and detour significantly.

**Termination**: stop when destination is popped from the queue. At that
point `g(dest)` is optimal.

### 5.4 Bidirectional A*

Run forward A* from source and backward A* from destination. Each direction
uses its own heuristic:

- Forward: `h_f(u) = lower bound on cost(u → dest)`
- Backward: `h_b(u) = lower bound on cost(u → source)`

**Termination condition**:

```
stop when: max(f_f(top_f), f_b(top_b)) >= μ
```

Proof: any undiscovered s→t path must cross at least one unsettled frontier.
If it crosses the forward frontier at node v: `cost(path) >= f_f(v) >=
f_f(top_f)`. If `f_f(top_f) >= μ`, no undiscovered path can improve on μ.
Same argument holds symmetrically for the backward direction. The heuristic
being admissible (`h <= actual cost`) is what makes `f_f(v)` a valid lower
bound on path cost.

Note: Bidirectional A* is complex to implement correctly and rarely used in
production. CH achieves better results without heuristics.

### 5.5 ALT (A* with Landmarks and Triangle Inequality)

Precompute exact road distances from every node to a small set of landmark
nodes (~16–20, placed far apart at high-degree locations).

At query time, use these as A* heuristic via the triangle inequality on road
distances:

```
h(u, dest) = max over all landmarks L of: |d(u, L) - d(dest, L)|
```

**Why it's admissible**: for any landmark L, triangle inequality gives:
`d(u, dest) + d(dest, L) >= d(u, L)`, therefore
`d(u, dest) >= d(u, L) - d(dest, L)`. Taking absolute value and max over
all landmarks gives the tightest admissible lower bound from the landmark set.

ALT heuristics are far tighter than straight-line because `d(u, L)` is the
actual road distance, reflecting real network geometry — detours, rivers,
mountain passes. ~10–50× speedup over plain A*.

Handles live traffic well: just rerun Dijkstra from each landmark when costs
change. Simpler to implement than CH, but slower on large graphs.

### 5.6 Contraction Hierarchies (CH)

The dominant production algorithm. Heavy preprocessing, ~1ms queries on
planet-scale graphs.

#### Preprocessing — Node Contraction

Every node is contracted exactly once, in order of increasing importance.
Contracting a node v means:

1. Remove v from the **active graph** (the working graph used for witness
   searches)
2. For each pair of active neighbors (xᵢ, yⱼ) through v:
   - Run **witness search**: Dijkstra from xᵢ ignoring v, bounded by
     `cost(xᵢ,v) + cost(v,yⱼ)` and a hop limit
   - If no witness found: add shortcut edge `xᵢ→yⱼ` to both the active
     graph and the **augmented graph**, storing v as the via-node
3. Assign `rank(v) = contraction_counter++`

The augmented graph retains all original edges and all added shortcuts.
Original edges are never removed from the augmented graph.

#### Node Importance Score

Determines contraction order. Lower score = contract sooner.

```
importance(v) = w1 × edge_difference
              + w2 × contracted_neighbors
              + w3 × search_space_size
```

- **Edge difference**: `shortcuts_added - edges_removed`. Measures how much
  the graph grows when v is contracted. Negative means the graph shrinks.
- **Contracted neighbors**: count of already-contracted neighbors. More
  contracted neighbors = v is more isolated = cheaper to contract now.
- **Search space size**: nodes reached by a local Dijkstra from v ×
  nodes reached from v on reversed graph. Approximates how central v is.

A **lazy priority queue** is used: recompute importance of a node only when
it is about to be popped, since neighbor contractions may have changed its
score. If it's no longer the minimum after recomputation, reinsert and pop
the next minimum.

#### Query — Bidirectional Upward Search

The augmented graph encodes the property: every shortest path goes upward
(increasing rank) from source, reaches a peak node, then goes downward to
destination.

Query runs two simultaneous Dijkstra searches:

- **Forward**: from source, only follow edges to nodes with higher rank
- **Backward**: from destination, only follow edges to nodes with higher rank
  (on reversed graph)

Both searches climb toward the same small set of high-rank nodes (highway
junctions, major interchanges) and meet near the top.

Update `μ = min(μ, g_f(u) + g_b(u))` whenever a node is settled by both.

**Termination**: `g_f(top_f) + g_b(top_b) >= μ` — clean Bidirectional
Dijkstra condition, no heuristic needed.

Typical nodes settled per query: **200–2000** (vs ~6 million for plain
Dijkstra). The upward constraint is responsible for this — not the larger
edge count.

**Path unpacking**: shortcuts store their via-node. Unpack recursively:
`shortcut(u→w via v)` → `u→v→w` → unpack u→v and v→w if also shortcuts.

#### Live Traffic Integration — Metric Separation

Pure CH bakes edge costs into both the node ordering (importance score uses
witness searches which use costs) and the shortcut existence decision. Changing
costs invalidates both, requiring full recontraction (~hours).

Production systems use metric separation:

- **Topology phase (once)**: determine node ordering and add shortcuts
  conservatively — include all possible shortcuts regardless of witness search.
  Structure is topology-only, independent of costs.
- **Customization phase (every ~5 min, ~1 second)**: sweep bottom-up through
  the hierarchy, recomputing shortcut costs from current edge costs:
  ```
  cost(xᵢ→yⱼ via v) = cost(xᵢ→v) + cost(v→yⱼ)
  ```
  No graph search — pure bottom-up DP, fully parallelizable.

Query correctness is maintained because Dijkstra naturally picks the
minimum-cost edge. Stale or suboptimal shortcuts have inflated costs and are
never chosen.

#### Augmented Graph Size

For road networks (low average degree ~2.5, low highway dimension):

```
Original graph:  ~2.5 edges per node
Augmented graph: ~5–8 edges per node  (2–3× growth)
```

The modest growth is a property of road networks — most nodes are local with
few connections and require zero or few shortcuts. Only high-rank junctions
generate many shortcuts, and there are few of those.

#### CH vs Other Algorithms

| Algorithm | Precomputation | Query nodes settled | Handles live traffic |
|---|---|---|---|
| Dijkstra | None | ~6M (country) | Yes |
| Bidirectional Dijkstra | None | ~1M | Yes |
| A* | None | ~500K | Yes |
| ALT | Landmark distances | ~50K | Yes (rerun landmark Dijkstra) |
| CH (pure) | Hours | ~500 | No (recontract needed) |
| CH (metric sep.) | Hours (once) + seconds | ~500 | Yes (customization) |
| Hub Labeling | Very heavy | ~microseconds | Hard |

### 5.7 Hub Labeling

For every node u, precompute two small sets of (hub_node, distance) pairs:
- `F(u)`: forward hubs — nodes on shortest paths from u to anywhere
- `B(u)`: backward hubs — nodes on shortest paths from anywhere to u

Correctness property: for any (s, t) pair, `F(s) ∩ B(t)` is non-empty and
contains a hub on the optimal s→t path.

Query = set intersection of F(s) and B(t) → microseconds.

Trade-off: preprocessing is very expensive, memory is ~100GB for planet scale.
Used when query latency is the only constraint and memory is abundant.

---

## 6. Production Systems

### Open Source Routing Engines

| Engine | Algorithm | Notes |
|---|---|---|
| OSRM | CH or MLD (CRP variant) | Fast, production-grade, easy Docker setup |
| Valhalla | Tiered CH + time-dependent | Used by Mapbox, supports turn-by-turn |
| GraphHopper | CH + landmarks | JVM-based, flexible profiles |

### Scaling

CH query is stateless and read-only after preprocessing. The augmented graph
for a country fits in ~1.5GB RAM. Routing servers are horizontally scaled —
each server holds the full graph in memory and handles queries independently.
No coordination needed between servers.

### Data Sources

- **OpenStreetMap**: free, planet-scale, updated daily. Used by OSRM,
  Valhalla, GraphHopper. Download regional extracts from geofabrik.de.
- **HERE / TomTom**: commercial, higher accuracy in some regions, licensing
  required.

---

## 7. End-to-End Architecture Summary

```
[Driver GPS] → Kafka → Map Matching (HMM/Viterbi) → Matched segments
                                                           ↓
                                              Traffic aggregation (Flink)
                                                    ↙           ↘
                                            Live traffic      Historical traffic
                                            (Redis, 5min)     (TimescaleDB)
                                                    ↘           ↙
                                             CH Customization (~1s)
                                                        ↓
                                             Routing engine (OSRM)
                                                        ↓
                                            Route API → Client map (Leaflet)
```
