# Geo Index: Types, Algorithms, and Industrial Use

## Why Geo Indexing Exists

Spatial data (lat/lng coordinates) cannot be efficiently indexed by a
standard B-tree. A B-tree sorts on one dimension — but a location has
two dimensions (latitude and longitude). To answer "find all points
within 1km of here", you need a way to map 2D space into something a
database can range-scan efficiently.

Geo indexing solves this by encoding 2D coordinates into a 1D value
(integer or string) that preserves spatial locality — nearby points
get nearby index values — so a range scan in the index approximates a
spatial query.

---

## Core Concepts

### Redundancy and Locality

The key property any geo index must have:

> **Spatially close points should have similar index values.**

If two drivers are 10 meters apart, their index values should be close
enough that a single range scan finds both. If nearby points have
wildly different index values, you need many separate range scans —
defeating the purpose of the index.

No 2D-to-1D mapping can perfectly preserve locality everywhere — some
boundary artifacts always exist. Different geo index schemes make
different tradeoffs on how bad these artifacts are.

### The Query Pattern

All geo indexes reduce spatial queries to one operation:

```
spatial region (circle, polygon, viewport)
  → compute covering set of index cells
  → WHERE index_value IN (ranges)
  → filter exact geometry in application code
```

The index narrows candidates; application code does exact filtering.

---

## Geo Index Types

### 1. Geohash

**Algorithm:**

Geohash encodes a lat/lng pair into a short alphanumeric string using
a custom base32 alphabet (32 characters, 5 bits each):

```
0 1 2 3 4 5 6 7 8 9 b c d e f g h j k m n p q r s t u v w x y z
```

Encoding process:
1. Interleave bits of longitude and latitude alternately
2. Longitude bits at odd positions, latitude bits at even positions
3. Group into 5-bit chunks
4. Map each chunk to a base32 character

Each additional character adds precision:

```
1 char  → ~5,000km
4 chars → ~39km
6 chars → ~1.2km
8 chars → ~38m
9 chars → ~4.8m
10 chars → ~1.2m
```

**Grid shape:**

Because longitude and latitude bits alternate, each character adds
either 3 longitude splits + 2 latitude splits, or vice versa:

```
odd-length string  → 8×4 grid (wider than tall)
even-length string → 4×8 grid (taller than wide)
```

Cells are rectangular and alternate aspect ratio with each character.

**Neighbor queries:**

For proximity search, query the target cell plus its 8 neighbors
(4 cardinal + 4 diagonal). Neighbor computation is pure math — no
database involved. Each neighbor lookup becomes a range scan:

```
WHERE geohash LIKE 'u09tvw%'  →  BETWEEN 'u09tvw0...' AND 'u09tvwz...'
```

**Boundary artifacts:**

Points near a geohash cell boundary can have completely different
prefixes despite being physically close. The 9-neighbor approach
handles this correctly for circle queries, but the rectangular cell
shape means you always scan a larger area than your actual circle —
extra rows must be filtered in application code.

Near the poles, geohash cells become extremely elongated — a
prefix-6 cell at 80°N is far larger than at the equator, making the
radius-to-prefix mapping unreliable for polar regions.

**Storage:**

String column with B-tree index. Prefix queries map directly to
B-tree range scans.

**Best for:**
- Simple proximity search (restaurants, stores, people nearby)
- Grid-based aggregations and heatmaps
- Redis-backed real-time lookups (Redis GEO uses geohash internally)
- Any use case where a standard database is already in use

**Industrial examples:**
- Yelp (store search)
- Foursquare (venue proximity)
- Tinder (people nearby)
- Any app using Redis GEORADIUS

---

### 2. Quadtree

**Algorithm:**

A quadtree recursively divides 2D space into 4 quadrants (NW, NE,
SW, SE). Each node splits into exactly 4 children until each leaf
cell contains fewer than a threshold number of points.

```
+--------+--------+
|        |        |
|   NW   |   NE   |
|        |        |
+--------+--------+
|        |        |
|   SW   |   SE   |
|        |        |
+--------+--------+
each quadrant subdivides again if too many points
```

The tree depth is **variable** — dense urban areas subdivide deeper
than sparse rural areas. This makes quadtrees naturally adaptive to
uneven data distribution.

**Key property:**

Unlike geohash (fixed grid), the quadtree structure adapts to where
data actually is. A city center with 10,000 POIs splits many more
times than an empty ocean region.

**Geohash relationship:**

Geohash is essentially a serialized quadtree — each character
encodes two levels of quad splits (one longitude, one latitude).
The string prefix hierarchy IS a quadtree encoded as a string.

**Best for:**
- Points of Interest (POI) databases with very uneven density
- In-memory spatial indexes
- Game engines (collision detection, visibility)
- Map rendering (deciding which tiles to load)

**Industrial examples:**
- OpenStreetMap rendering pipeline
- Game engines (Unity, Unreal spatial partitioning)
- Apple Maps internal tile indexing

---

### 3. Google S2 (Hilbert Curve)

**Algorithm:**

S2 maps the Earth's surface to a 1D integer index using a Hilbert
curve — a space-filling curve that visits every cell in a 2D grid
exactly once while always moving to an adjacent cell.

**Step 1 — project sphere onto a cube:**

The Earth sphere is projected onto 6 cube faces. Each face is a 2D
square that can be independently indexed.

```
        +------+
        | top  |
+-------+------+-------+------+
| left  | front| right | back |
+-------+------+-------+------+
        |bottom|
        +------+
```

**Step 2 — apply quadratic transform:**

Raw projection onto cube faces causes cell size distortion near face
edges. S2 applies a quadratic transform to normalize cell sizes,
making cells roughly equal area globally.

**Step 3 — Hilbert curve ordering:**

Within each face, cells are numbered by their position along a
Hilbert curve. The Hilbert curve is constructed recursively — at each
level, each cell subdivides into 4 children, with the U-shape
rotated/reflected to maintain curve continuity.

```
level 1 (2×2):    level 2 (4×4):
0 - 1              0  1 14 15
    |              3  2 13 12
3 - 2              4  7  8 11
                   5  6  9 10
```

Consecutive numbers are always spatially adjacent — no jumps.

**Step 4 — 64-bit cell ID:**

Each cell is identified by a single 64-bit integer:

```
bits 63-61  →  face (0-5)          3 bits
bits 60-1   →  Hilbert position   60 bits  (2 bits × 30 levels)
bit  0      →  sentinel (encodes level)
```

The sentinel bit encodes the cell level implicitly — the lowest set
bit position indicates depth.

**Cell levels:**

S2 has 31 levels (0-30):

```
level  0  →  entire face (~85M km²)
level 10  →  ~50km²
level 13  →  ~1km²
level 20  →  ~1m²
level 30  →  ~1cm²
```

**Converting lat/lng to cell ID:**

```
(lat, lng)
  → trig → (x, y, z) on unit sphere
  → largest component → cube face + (u,v)
  → quadratic transform → (s,t)
  → scale → (face, i, j) grid position
  → Hilbert encoding → 64-bit cell ID
```

All steps are pure math — deterministic, no database lookup.

**Region covering:**

To query a spatial region (circle, polygon), S2 computes a minimal
set of cells that cover the region:

```
S2RegionCoverer.getCovering(region)
  → list of S2CellUnion ranges
  → each cell = contiguous range [rangeMin, rangeMax]
  → WHERE cell_id BETWEEN min AND max
```

The covering uses a mix of cell levels — coarse cells for the
interior, fine cells near the boundary — minimizing both the number
of ranges and wasted area.

**Face boundary handling:**

The Hilbert curve is designed to exit one face and enter the adjacent
face at a compatible orientation — the curve is continuous across face
boundaries. Region covering automatically includes cells from multiple
faces when a region spans a face boundary.

**Advantages over geohash:**
- Uniform cell size globally (no polar distortion)
- Arbitrary shape covering (circles, polygons, corridors)
- Mathematically guaranteed coverage
- Better locality at region boundaries

**Storage:**

`BIGINT` / `INT64` column with B-tree index. Spatial queries become
integer range scans.

**Best for:**
- Global applications requiring uniform cell size
- Complex shape queries (polygons, corridors)
- Mobile applications (Uber, Google Maps)
- Any use case where geohash polar distortion is a problem

**Industrial examples:**
- Google Maps (invented S2)
- Uber (driver/rider matching, surge zone polygons)
- Pokémon Go (spawn zone polygons)
- Disney+ (geo licensing region enforcement)

---

### 4. H3 (Uber's Hexagonal Grid)

**Algorithm:**

H3 divides the Earth into hexagonal cells at multiple resolutions.
Unlike squares, hexagons have a key geometric property:

> All 6 neighbors of a hexagon are equidistant from its center.

With squares, diagonal neighbors are ~1.41× farther than cardinal
neighbors. With hexagons, all neighbors are the same distance.

H3 uses a custom base32 encoding for cell IDs, designed to fit within
a 64-bit integer at all resolutions.

**Resolutions:**

```
resolution  0  →  ~4M km² (continent)
resolution  5  →  ~252 km²
resolution  9  →  ~0.1 km² (neighborhood)
resolution 12  →  ~0.0003 km² (building)
resolution 15  →  finest
```

**Advantages:**
- Uniform neighbor distances (better for driver dispatch)
- Natural hexagonal grid for analytics and visualization
- Open-sourced by Uber, widely adopted

**Best for:**
- Ride-sharing driver dispatch
- Surge pricing zone calculation
- Fleet distribution analytics
- Heatmap visualization

**Industrial examples:**
- Uber (H3 creator, used for dispatch and surge pricing)
- Snowflake (native H3 functions)
- BigQuery (native H3 functions)
- DoorDash (delivery zone analytics)

---

### 5. R-Tree / PostGIS

**Algorithm:**

An R-tree indexes spatial objects by storing minimum bounding
rectangles (MBRs) at each node. Each internal node contains the MBR
of all its children. Queries traverse the tree, pruning branches whose
MBR does not intersect the query region.

Unlike geohash/S2/H3 (which encode points as integers), R-trees
natively index:
- Points
- Line segments
- Polygons
- Any geometry

**PostGIS:**

PostGIS extends PostgreSQL with a full suite of spatial types and
functions, backed by a GiST (Generalized Search Tree) R-tree index.

```sql
-- spatial query with exact distance filter
SELECT * FROM places
WHERE ST_DWithin(
    location,
    ST_Point(2.35, 48.85)::geography,
    1000   -- meters
);
```

PostGIS handles the R-tree traversal, cell covering, and exact
distance filtering internally — no application-level geo math needed.

**Best for:**
- Relational databases (PostgreSQL)
- Complex geometry queries (polygon intersection, line buffering)
- Mixed geometry types (points, lines, polygons in one index)
- When PostGIS is already in the stack

**Limitations:**
- Single-node — harder to shard across multiple machines
- Not designed for real-time high-frequency point updates

**Industrial examples:**
- OpenStreetMap data storage and queries
- Any GIS application built on PostgreSQL
- Government/mapping agencies

---

### 6. BKD-Tree (Elasticsearch)

**Algorithm:**

A BKD-tree (Bulk k-d tree) is a disk-optimized variant of a k-d tree.
A k-d tree splits space into 2 halves, alternating on each dimension:

```
depth 1: split on longitude
depth 2: split on latitude
depth 3: split on longitude
...
```

The BKD-tree is optimized for:
- Bulk construction (built from sorted data, not inserted one at a time)
- Read-heavy workloads
- Cache-efficient disk layout

Elasticsearch uses BKD-trees for all `geo_point` field queries
(`geo_distance`, `geo_bounding_box`, `geo_polygon`). Geohash in
Elasticsearch is a separate, legacy feature used only for
`geohash_grid` aggregations (heatmaps).

**Best for:**
- Elasticsearch-based search
- Log analytics with location data
- Full-text + geo combined queries

**Industrial examples:**
- Elasticsearch geo queries (all major users: Booking.com, LinkedIn)
- Lucene spatial module

---

### 7. Redis GEO

**Algorithm:**

Redis GEO stores coordinates as geohash integers in a sorted set,
using the geohash score as the sorted set score. Commands:

```
GEOADD  key lng lat member
GEORADIUS key lng lat radius unit
GEODIST key member1 member2 unit
```

Internally uses 52-bit geohash precision — designed to fit within a
double's 52-bit mantissa without precision loss.

**Best for:**
- Real-time position tracking (moving objects)
- Small radius proximity queries
- When Redis is already in the stack
- Sub-millisecond latency requirements

**Limitations:**
- In-memory only (data loss risk without persistence config)
- No complex shape queries
- Degrades for very large radius queries

**Industrial examples:**
- Food delivery apps (real-time courier tracking)
- Ride-sharing rider-facing driver dots
- Any Redis-first architecture

---

### 8. Z-Order Curve (Morton Code)

**Algorithm:**

Similar to Hilbert curve, Z-order interleaves the bits of latitude
and longitude into a single integer. The resulting curve looks like a
Z or N pattern at each level of recursion.

Simpler to compute than Hilbert, but has worse locality — the Z
pattern creates larger "jumps" at fold boundaries than Hilbert.

**Best for:**
- Simple implementations where Hilbert is too complex
- Some game engines
- Legacy systems

---

### 9. Voronoi Diagram

**Algorithm:**

Given a set of seed points (facilities, warehouses, hospitals),
Voronoi divides space into regions where each region contains all
points closer to its seed than to any other seed.

```
query: "which warehouse should fulfill this order?"
→ find which Voronoi cell the order location falls in
→ that cell's seed = the nearest warehouse
```

**Best for:**
- Nearest facility assignment
- Service area calculation
- Static seed points (doesn't update well dynamically)

**Industrial examples:**
- Logistics (warehouse assignment)
- Telecom (cell tower coverage areas)
- Retail (store catchment area analysis)

---

## Comparison Table

| Index | Encoding | Shape | Polar distortion | Complex shapes | Real-time updates | Best DB |
|-------|----------|-------|-----------------|----------------|-------------------|---------|
| Geohash | base32 string | rectangle | yes | no | yes | any |
| Quadtree | tree structure | rectangle | yes | no | moderate | in-memory |
| S2 | INT64 | ~square | no | yes | yes | any |
| H3 | INT64 | hexagon | minimal | yes | yes | any |
| R-Tree | tree structure | any | no | yes | moderate | PostgreSQL |
| BKD-Tree | tree structure | any | no | yes | bulk only | Elasticsearch |
| Redis GEO | geohash int | circle | yes | no | yes | Redis |
| Z-order | INT64 | rectangle | yes | no | yes | any |
| Voronoi | polygon regions | any | no | yes | no | PostgreSQL |

---

## Industrial Use Case Mapping

```
Simple store/venue search        →  Geohash or Redis GEO
Real-time driver tracking        →  Redis GEO + H3 or S2
Surge pricing zones (polygons)   →  S2 or H3
Global mapping application       →  S2 (no polar distortion)
Fleet analytics / heatmaps       →  H3
Full GIS / polygon queries       →  PostGIS R-tree
Search + geo combined            →  Elasticsearch BKD-tree
Nearest warehouse assignment     →  Voronoi
Game engine spatial              →  Quadtree
```

---

## Key Decision Factors

**Use geohash if:**
- Simple radius query at human scale
- Standard database already in use
- Team is familiar with it
- No polar region concerns

**Use S2 if:**
- Need arbitrary shape coverage (polygons, corridors)
- Global application (polar regions matter)
- Mobile app with device-side computation
- Need mathematically guaranteed coverage

**Use H3 if:**
- Hexagonal grid analytics
- Uber-style dispatch where equal neighbor distance matters
- BigQuery/Snowflake integration

**Use PostGIS if:**
- PostgreSQL is already in the stack
- Mixed geometry types (points, lines, polygons)
- Complex GIS operations

**Use Redis GEO if:**
- Sub-millisecond real-time position lookups
- Redis already in stack
- Simple radius queries only

---

## Compression and Index Storage

All point-based geo indexes (geohash, S2, H3, Z-order) follow the
same storage pattern:

1. Encode lat/lng as an integer or string
2. Store in a B-tree indexed column
3. Spatial query → compute covering ranges → B-tree range scans

The encoding is pure math on the application side. The database only
sees integer or string range queries — no spatial awareness required.

This is why geo indexes are so portable: any database with a B-tree
index can serve spatial queries efficiently, as long as the
application handles the encoding and covering computation.
