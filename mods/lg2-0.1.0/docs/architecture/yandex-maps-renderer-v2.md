# Yandex Maps Renderer v2 — architecture and implementation plan

Status: **implemented baseline; this document is the maintained architecture contract**

This document defines the replacement for the removed Yandex Maps rendering subsystem. The old tile/LOD/top-down implementation must not be revived piecemeal. v2 is a clean subsystem with strict isolation from camera rendering and monitor UI rendering.

## 1. Goals

The renderer must:

1. Render terrain through Minecraft's **vanilla client rendering engine** (`ClientLevel` + `LevelRenderer` + vanilla section meshes, block-entity renderers and entity renderer for `ItemDisplay`). It must not become a custom block-color/map-color renderer.
2. Render a deterministic orthographic top-down image which does not change because of:
   - time of day;
   - rain/thunder;
   - clouds/sky effects;
   - particles;
   - ordinary entities;
   - the player's current camera or current world.
3. Keep vanilla block rendering, biome tinting, fluids, block entities and `ItemDisplay` rendering.
4. Use the dedicated renderer client **and** optionally regular players with the Lostglade client mod as distributed rendering workers.
5. Never generate or load a server chunk merely because the map wants an image. Only chunks which already exist in world storage may enter a map snapshot.
6. Eventually cover every existing/generated overworld chunk while keeping changed areas reasonably fresh. Hours of delay are acceptable; stalls/starvation are not.
7. Render only the Overworld in v1 while making dimension identity part of every persistent/protocol type so Nether/End can be added without another storage/protocol rewrite.
8. Keep map display responsive even when no renderer is available: the monitor UI always reads the last committed tile cache and never waits for GPU work.

## 2. Non-goals for v1

- No Nether or End rendering yet.
- No live-map guarantee. The cache is intentionally eventually consistent.
- No synchronous render triggered by panning/zooming a monitor.
- No per-zoom GPU rendering.
- No sharing a mutable camera shadow world with the map renderer.
- No attempt to preserve the old cache format or old map-tile protocol.
- No support for a volunteer renderer while an incompatible shader pack is active.

## 3. Hard invariants

These are architecture rules, not tuning preferences.

### 3.1 Map rendering may not load/generate chunks

The map subsystem must never call chunk-loading/generation entry points such as `ServerLevel#getChunk(...)`, ticket APIs, or code which creates a `ChunkHolder` in order to satisfy a render request.

Allowed sources:

- read-only region/chunk storage reads;
- region-file header scans;
- already-materialized data explicitly passed into the map subsystem without requesting a load.

For v1, prefer a **disk snapshot** even when a chunk is currently loaded. This intentionally trades freshness for correctness and isolation. A changed chunk becomes renderable after Minecraft has saved the new state. This removes races with a live `LevelChunk` and makes the snapshot coherent.

A dedicated architecture regression test must fail if code in the map-render package starts using forbidden chunk-loading/ticket APIs.

### 3.2 Monitor UI never owns world rendering

Opening, dragging or zooming Yandex Maps may:

- read immutable cached images;
- record a short-lived demand/visibility hint for the background scheduler.

It may not:

- read region files;
- create a shadow world;
- wait for a renderer client;
- compile chunks;
- start a GPU render;
- invalidate a camera renderer session.

### 3.3 One authoritative GPU scale

There is exactly one GPU-rendered base level in the current render profile.

Current canonical geometry:

- world tile: **16 x 16 blocks** (**1 x 1 chunk**);
- source image: **256 x 256 pixels**;
- base resolution: **16 pixels/block**, preserving the native texel grid of standard 16x16 block textures;
- snapshot halo: one existing-chunk ring around the source tile where available;
- UI-only magnification may enlarge L0 up to **256 display pixels/block** without any additional GPU render.

All lower-detail zoom levels are generated on the server from committed base tiles. They are never independently rendered by clients.

This eliminates scale-dependent world snapshots, LOD-specific chunk ownership and cross-zoom color differences.

### 3.4 Map scene is isolated from camera scenes

Do not add map jobs back to `RendererBotCameraSystem` or its shadow-state scheduler.

The map renderer gets:

- its own server service;
- its own worker registry;
- its own protocol messages;
- its own client `ClientLevel`/`LevelRenderer` lifecycle;
- its own render target;
- its own scheduling and failure policy.

Sharing small stateless utilities is fine (for example packet codecs or Polymer patching), but map world state must not be shared with camera/photo/video/live-stream state.

### 3.5 A tile is immutable until an atomic replacement

A committed base tile never changes in place while a monitor is reading it.

New results are written as a temporary file, validated, fsynced/closed, then atomically renamed over the previous version together with metadata. Until that commit succeeds, every viewer keeps seeing the previous complete tile.

### 3.6 Render profile identity is part of the cache identity

Tiles produced by incompatible resource/render profiles must never be mixed.

The active profile includes at minimum:

- map render profile version;
- Minecraft/Lostglade protocol version;
- texture/resource-pack fingerprint;
- relevant renderer capability fingerprint.

A dedicated renderer establishes the canonical fingerprint when available. Volunteer clients may render only when their fingerprint matches the active canonical profile.

## 4. Package boundaries

Create a new subsystem instead of growing `MonitorYandexMapsRuntime`.

Recommended server package:

`com.lostglade.server.maprender`

Recommended classes:

- `YandexMapRenderService`
- `MapChunkExistenceIndex`
- `MapDirtyTracker`
- `MapRenderScheduler`
- `MapRenderWorkerRegistry`
- `MapSnapshotRepository`
- `MapSnapshotPacketBuilder`
- `MapTileStore`
- `MapPyramidBuilder`
- `MapDemandTracker`
- `MapRenderMetrics`
- `MapRenderProfile`
- `MapTileKey`
- `MapTileMetadata`

Recommended client package:

`com.lostglade.client.maprender`

- `YandexMapRenderClient`
- `YandexMapRenderWorld`
- `YandexMapVanillaTopDownRenderer`
- `YandexMapRenderContext`
- `YandexMapRenderBudgetController`
- `YandexMapShaderGuard`
- `YandexMapRenderValidator`

Recommended networking file:

- `YandexMapRenderPayloads`

`MonitorYandexMapsRuntime` stays responsible only for app state (center/zoom/markers/GPS), cached-image composition and user input.

## 5. Persistent tile model

### 5.1 Base key

```text
MapTileKey(
    dimension,
    tileX,
    tileZ,
    renderProfileVersion
)
```

For v1 only `minecraft:overworld` is admitted by policy.

Coordinates are floor-divided in world space. With the 16-block base tile:

```text
tileX = floorDiv(blockX, 16)
tileZ = floorDiv(blockZ, 16)
```

Equivalently, one generated chunk maps to one canonical L0 tile.

Negative coordinates must be covered by tests.

### 5.2 Metadata

Each base tile stores metadata similar to:

```text
profileHash
renderedAt
sourceFingerprint
renderedRevision
pixelWidth / pixelHeight
existingChunkMask
resultChecksum
workerId (diagnostic only)
```

Dirty/scheduler state is separate from committed tile metadata:

```text
dirtyRevision
dirtySince
lastChangeAt
changeWeight
lastDiscoveredAt
lastRenderedAt
nextEligibleAt
failureCount
visibleUntil
```

### 5.3 Storage layout

Suggested world-local layout:

```text
<world>/lostglade/yandex_maps/v2/
  minecraft_overworld/
    render-v<renderProfileVersion>/
      profile-<resourceHash>/
        base/
          <tileX>/<tileZ>/current.json
          <tileX>/<tileZ>/g-<generation>.png
          <tileX>/<tileZ>/g-<generation>.meta.json
        pyramid/
          l1/...
          l2/...
          ...
```

`base/` is authoritative. `pyramid/` is derived/rebuildable cache and may be deleted at any time.

Changing the base render contract creates a new physical `render-v<version>` namespace before the resource-profile namespace. Never silently reinterpret or probe old-version files as current tiles.

## 6. Existing-chunk discovery without loading chunks

### 6.1 Startup index

`MapChunkExistenceIndex` scans Overworld `.mca` region headers asynchronously. Reading region location/timestamp tables is enough to discover candidate chunk coordinates without materializing them into the server world.

For every candidate chunk store:

- chunk coordinate;
- region storage timestamp / cheap storage stamp;
- owning base tile.

When a candidate is actually snapshotted, validate that its stored status is renderable (`FULL` or the exact current-version equivalent). A header entry alone must not cause an unfinished proto chunk to be treated as map terrain.

### 6.2 Restart dirtiness detection

Persist the source fingerprint in tile metadata. On startup, compare current region header stamps against the committed fingerprint.

This lets the server identify changed tiles after a restart without decompressing every chunk and without marking the entire explored world dirty.

### 6.3 Runtime dirty events

When already-loaded gameplay changes happen, `MapDirtyTracker` only records that the owning tile became dirty; it does not snapshot immediately.

Useful dirty signals:

- block state changes;
- block-entity changes;
- chunk save/full-save completion;
- `ItemDisplay` spawn/despawn/move/data changes;
- entity-region save completion;
- newly saved generated chunks.

The scheduler should not repeatedly render a dirty tile while its on-disk source fingerprint is unchanged. It waits for the corresponding save to advance the storage fingerprint.

This is important: **dirty event != renderable new snapshot**.

## 7. Read-only snapshot pipeline

A render job consumes a coherent on-disk snapshot.

### 7.1 Source region

For the canonical 16-block base tile, source terrain is exactly one chunk. Add one chunk of halo on every side where an already-existing chunk is present.

The halo exists only to give vanilla meshing/light/face-culling appropriate neighbours. Missing/nonexistent halo chunks remain missing. The renderer must not synthesize generated terrain and must not ask the server to create them.

### 7.2 Chunk data

Read chunk NBT through Minecraft's storage layer only. Do not route through the live server chunk-loading path.

The snapshot builder produces a worker-specific vanilla client representation:

- chunk sections / block states;
- biomes;
- saved sky/block light;
- heightmaps as required by vanilla packet construction;
- block entity data;
- any additional vanilla state required by `ClientboundLevelChunkWithLightPacket`.

The safe target is a standalone, non-registered snapshot object used solely to construct/encode the vanilla client chunk packet. It must never be inserted into `ServerLevel`/`ChunkMap`.

Use the existing Polymer/packet patching path when encoding for a specific worker so server-side custom states become the same client-visible fallback representation that the worker would normally receive.

### 7.3 Block entities

Block entities are part of the map contract and must survive the read-only snapshot path. Add a regression fixture containing at least:

- chest/barrel;
- sign/hanging sign;
- banner or another visually obvious block entity;
- one custom/Polymer-visible block entity if the project uses one in normal play.

The test must verify that the vanilla client scene contains/rendered them, not merely that NBT was read.

### 7.4 ItemDisplay entities

Ordinary entities are excluded, but `Display.ItemDisplay` is included.

For unloaded chunks, item displays must eventually be read from the world's saved entity storage, not by loading those entities into `ServerLevel`.

The server sends only the minimal vanilla entity packets required for saved item displays in the tile + halo:

- add entity;
- metadata/data values;
- transform-related state;
- passengers/link packets only if actually required for a visible `ItemDisplay` configuration.

All packets pass through the same worker-specific packet patching rules.

No zombie/player/animal/projectile/etc. packets belong in a map scene.

## 8. Distributed render worker model

Map volunteering is independent from camera volunteering.

### 8.1 Separate client settings

Add Lostglade client settings approximately like:

```text
mapRendererMode = OFF | IDLE_ONLY | ALWAYS
mapRendererMinFps = 50
mapRendererMaxJobsPerMinute = 6
```

Recommended defaults for ordinary players:

```text
OFF
```

A dedicated hidden renderer may enable map rendering by dedicated-renderer policy, but it still has to pass compatibility checks.

The Lostglade settings screen should show map GPU contribution separately from camera/photo contribution and show a current status reason such as:

- disabled by user;
- shader pack active;
- resource profile mismatch;
- waiting for work;
- rendering map tile;
- throttled by FPS/budget.

### 8.2 Server settings

Recommended server-side settings:

```text
yandexMapRendererAllowPlayerVolunteers = true
yandexMapRendererMaxGlobalInFlight = 4
yandexMapSnapshotThreads = 2
yandexMapVisibleDemandSeconds = 30
yandexMapMaxFailureBackoffMinutes = 60
```

Numbers should be sanitized and capped like existing config fields.

### 8.3 Capability handshake

Do not overload the existing camera volunteer boolean.

Create a dedicated map capability/status payload containing at least:

```text
mapProtocolVersion
enabled
mode
shaderCompatible
resourceProfileHash
maxTilePixels
clientBuildFingerprint
```

Capability updates are resent when:

- the player toggles map rendering;
- shader state changes;
- resource packs reload;
- the connection joins/rejoins.

The server registry treats each player as a worker with `maxInFlight = 1` in v1.

Multiple different clients may therefore render different tiles concurrently, but one Minecraft client never has two map scenes fighting its render thread.

### 8.4 Shader policy

v1 policy is conservative:

- if a known shader pack is active, the client reports `shaderCompatible=false` and receives no map work;
- do **not** dynamically turn a player's shaders off/on around a background render;
- do not let map work touch the player's main render pipeline configuration.

For Iris, use an optional integration/reflection probe rather than a hard runtime dependency if practical. If an installed renderer/shader mod cannot be confidently classified, prefer rejecting map work over producing inconsistent tiles.

### 8.5 Resource-profile consistency

A volunteer must not create visually inconsistent neighbours because they use different packs.

Compute a stable fingerprint over relevant enabled resource packs / renderer identity. The dedicated renderer establishes the preferred canonical profile when present.

If there is no dedicated renderer, the service may establish a canonical profile from the first eligible worker for that server session. Workers with a different hash stay idle for map jobs.

A profile change creates a new cache namespace; do not mix results in place.

## 9. Network/job state machine

Use explicit leases and revisions.

Suggested flow:

```text
SERVER                         CLIENT WORKER
  | MapRenderJobOffer ----------> |
  | <--------- Accept/Reject      |
  | MapSceneInit ---------------->|
  | MapSceneChunkBatch ---------->|
  | MapSceneItemDisplayBatch ---->|
  | MapSceneReady --------------->|
  |                               | build vanilla scene
  |                               | wait compile/upload barrier
  |                               | prime render
  |                               | final render + readback
  | <--------- MapTileResult      |
  | validate + atomic commit      |
  | MapJobComplete/implicit ack   |
```

A server lease has:

```text
jobId
workerUuid
tileKey
snapshotFingerprint
assignedDirtyRevision
expiresAt
profileHash
```

Disconnect/reject/timeout releases the lease and applies backoff. Never spin immediate retries on the same failing worker.

If gameplay changes the tile while an older job is rendering:

- accept a valid result for its assigned snapshot;
- commit it as an intermediate version;
- leave the tile dirty because `currentDirtyRevision > renderedRevision`.

This avoids throwing away expensive work while still guaranteeing eventual refresh.

## 10. Client map scene lifecycle

Robustness is more important than shaving scene-construction milliseconds.

### 10.1 Ephemeral world per job in v1

Each accepted base-tile job creates a fresh map render scene:

- dedicated `ClientLevel`/shadow level;
- dedicated `LevelRenderer`;
- dedicated render buffers/feature dispatcher as needed;
- dedicated map render target or a safely reusable target whose contents are always explicitly cleared;
- fixed environment state.

Destroy the scene after result/failure.

Do not retain chunks from a previous tile in the next tile scene in v1. Cross-job scene reuse was a major class of contamination risk in the old design and is not worth the optimization until the new system is proven stable.

### 10.2 No mutation of the player's real world

Never replace `Minecraft.level`, `Minecraft.player`, the player's live `LevelRenderer` or particle engine.

Only narrowly scoped GPU/dispatcher state may be swapped while the offscreen map render is active, then restored in `finally` exactly like a transaction.

## 11. Vanilla top-down renderer contract

`YandexMapVanillaTopDownRenderer` uses the vanilla `LevelRenderer` and vanilla block/entity/block-entity rendering paths.

### 11.1 Camera/projection

For the canonical 16 x 16 block L0 tile:

- orthographic projection width = 16 blocks;
- orthographic projection height = 16 blocks;
- camera X/Z = exact tile center;
- camera Y = safely above the Overworld build ceiling;
- pitch = straight down;
- yaw fixed to one canonical orientation (north at the top of the image);
- near/far planes cover the full build height plus safety margin.

Do not derive camera height/projection from local terrain height. Every tile uses the same geometric contract so adjacent images line up exactly.

### 11.2 Deterministic environment

The map shadow level is initialized with fixed state, e.g.:

```text
gameTime = 0
dayTime = 6000
tickDayTime = false
raining = false
rainLevel = 0
thunderLevel = 0
partialTick = 0
```

Also during `YandexMapRenderContext`:

- fog: disabled / `FogMode.NONE`;
- sky: not rendered;
- clouds: not rendered;
- precipitation/weather pass: not rendered;
- particles: not rendered and the map scene particle engine is never ticked;
- block outline/selection: not rendered;
- hand/HUD/post UI: not rendered.

Lighting still comes from vanilla saved block/sky light and the fixed deterministic lightmap/environment.

### 11.3 Entity filter

During map render extraction/submission:

```text
allow: Display.ItemDisplay
deny: every other Entity type
```

Enforce this in two places:

1. server snapshot sends only item displays;
2. client map render context filters again.

Block entities are **not** part of the entity filter and remain enabled.

### 11.4 Animated textures

Do not modify the player's global texture atlas in the first implementation merely to freeze animated texture phases. The map's world/time/weather must be deterministic first.

If animated water/lava/resource-pack textures cause visible cross-tile phase seams, add a narrowly scoped texture-animation clock in a later hardening step. Do not solve this by globally pausing the player's textures.

## 12. Readiness barrier — preventing black/grey/white tiles

A received scene is not immediately renderable.

A job may publish pixels only after all of these are true for the job's scene revision:

1. server sent `MapSceneReady`;
2. all expected source/halo packets were applied;
3. vanilla `LevelRenderer` has completed visible-section rebuilds;
4. section compile queue is empty;
5. GPU upload queue is empty;
6. no newer scene revision arrived after the readiness check.

Then:

1. render one **prime frame** without readback;
2. on a later client render opportunity, render the final frame;
3. read back that final target.

Do not rely on arbitrary `sleep` or a fixed number of ticks as the main readiness condition.

## 13. Result validation

Both client and server validate results.

Minimum checks:

- exact expected dimensions;
- exact pixel byte length;
- sane alpha/data bounds;
- checksum;
- matching job/tile/profile/snapshot IDs.

Add a cheap pathological-frame guard. Example: if the source snapshot contains known non-air sections but the output is effectively one uniform color, reject it as a render failure rather than poisoning the cache.

Do not attempt to “repair” suspicious frames with brightness/white filters. A suspicious render is retried/quarantined; it is never color-corrected into the cache.

## 14. Base tile store and zoom pyramid

### 14.1 Base tile

Only the canonical **256x256 / 16 px-per-block** L0 image is GPU authoritative.

Store it losslessly (PNG is acceptable for v1). Client-to-server result may remain raw RGBA/ARGB because 256x256x4 = 256 KiB and avoids client-side image-codec complexity on the render thread. Encoding/storage can happen on a server IO worker.

### 14.2 Derived zooms

Build a power-of-two pyramid on CPU:

```text
L0: 16 world blocks  -> 256 px   (16 px/block)
L1: 32 world blocks  -> 256 px   (8 px/block)
L2: 64 world blocks  -> 256 px   (4 px/block)
L3: 128 world blocks -> 256 px   (2 px/block)
L4: 256 world blocks -> 256 px   (1 px/block)
L5: 512 world blocks -> 256 px   (0.5 px/block)
...
```

Every Ln tile is derived from four L(n-1) children.

When an L0 tile changes, invalidate only its ancestor chain. Rebuild ancestors lazily/background.

This is the only zoom/LOD system. It contains no Minecraft world rendering and no client jobs.

## 15. Priority and freshness scheduler

One opaque global score is easy to starve. Use **weighted fair lanes**, then a deterministic order inside each lane.

Recommended lanes:

1. `DIRTY` — previously rendered tiles whose saved source changed.
2. `NEW_COVERAGE` — existing/generated terrain with no base tile yet.
3. `VISIBLE_DEMAND` — existing tiles currently visible on a monitor and missing/stale.
4. `AUDIT` — oldest committed tiles / periodic consistency work.

Suggested weighted dispatch cycle:

```text
DIRTY          5
VISIBLE_DEMAND 4
NEW_COVERAGE   3
AUDIT          1
```

Weights are not strict percentages when a lane is empty.

### 15.1 Dirty ordering

Within `DIRTY`:

1. oldest `dirtySince` first;
2. then higher aggregated `changeWeight`;
3. then oldest `lastRenderedAt`;
4. stable tile-coordinate tiebreaker.

This deliberately makes **age primary** so a constantly-changing factory chunk cannot starve an older quiet change forever. Change volume still accelerates frequently modified areas among similarly aged jobs.

Suggested `changeWeight` accumulation uses logarithmic/saturating buckets rather than an unbounded counter.

### 15.2 New coverage ordering

Within `NEW_COVERAGE`:

- currently visible candidate first;
- otherwise discovered timestamp / deterministic region order;
- optional mild distance-from-spawn preference is acceptable, but must not become a starvation rule.

### 15.3 Visible demand

When a user pans/zooms, `MapDemandTracker` marks already-existing relevant base/pyramid tiles visible for roughly 30 seconds.

This is only a scheduling hint. The UI immediately shows whatever stale/partial cache exists. It never waits for this lane.

The lane weight caps interactive demand so somebody dragging the map continuously cannot consume 100% of background rendering.

### 15.4 Audit

Audit is intentionally low-rate. It can:

- compare current source fingerprints to metadata;
- repair missing derived pyramid tiles;
- requeue anomalously old tiles.

It must not blindly rerender unchanged base images.

## 16. Worker selection and GPU budget

### 16.1 Per-client arbitration

Even though camera and map services are separate on the server, they share the physical client GPU/main render thread.

Add a small client-local GPU work arbiter with priority:

```text
live camera/video > photo/camera capture > item icon > map background job
```

A map job is preemptible before its final render. It may keep downloaded snapshot data for a short grace period, but must not block realtime camera frames.

### 16.2 Ordinary player budget

`IDLE_ONLY` should require conditions such as:

- player FPS above configured threshold;
- no recent severe frame-time spike;
- no current high-priority Lostglade GPU work;
- optional short idle/no-input window.

Use a token/leaky-bucket budget (jobs or measured render milliseconds per minute), not a tight render loop.

The dedicated renderer may use a much less restrictive budget.

## 17. UI composition architecture

`MonitorYandexMapsRuntime` should be rebuilt as a thin UI state machine.

Responsibilities:

- center X/Z;
- zoom level;
- GPS state;
- marker state and marker-title chat flow;
- visible tile calculation;
- image composition from `MapTileStore`/pyramid;
- demand hints to scheduler.

It must not know about:

- renderer clients;
- shadow levels;
- section compilation;
- chunk NBT;
- GPU jobs.

Markers/GPS are drawn as an overlay after the cached terrain image. Moving a marker must never dirty a terrain tile.

### 17.1 Stable panning

A monitor frame is keyed by:

```text
center
zoom
viewport size
visible tile revision vector
overlay revision
```

Panning changes geometry/composition only. Pixel colors of an already committed tile never depend on camera movement. This directly prevents the old “pixels recolor while dragging the map” behaviour.

## 18. Failure handling

### 18.1 Tile backoff

Per tile:

```text
failure 1 -> short retry
failure 2 -> longer retry
failure 3+ -> exponential backoff capped at server setting
```

A new source fingerprint may clear/reduce the backoff because the problematic source changed.

### 18.2 Worker health

Track recent worker failures separately from tile failures.

Repeated protocol/render/readback failures temporarily cool down that worker. Do not repeatedly send the same tile to the same failing client when another compatible worker exists.

### 18.3 Lease timeout

Every accepted job has a deadline. Disconnect or timeout returns it to the queue without deleting the previous committed image.

## 19. Protocol/version strategy

Keep map protocol independent from camera payload records.

Suggested messages:

### C2S

- `MapRendererCapabilitiesC2S`
- `MapRenderJobDecisionC2S`
- `MapTileResultC2S`
- `MapTileFailureC2S`

### S2C

- `MapRenderJobOfferS2C`
- `MapSceneInitS2C`
- `MapSceneChunkS2C` or bounded batch payload
- `MapSceneItemDisplayPacketsS2C`
- `MapSceneReadyS2C`
- `MapRenderJobCancelS2C`

Large scene data should be bounded/chunked. The current canonical source is one chunk plus only the existing one-ring halo; do not create a single unbounded scene payload.

Bump protocol explicitly when the scene contract changes.

## 20. Dimension extensibility

Every key/protocol/storage path contains a dimension id even though v1 admission is:

```text
allowedDimensions = [minecraft:overworld]
```

Future dimensions can supply a `MapDimensionRenderPolicy`:

```text
dimensionId
cameraY / projection rules
background policy
chunk eligibility
special surface policy
```

Nether especially needs an explicit future decision about the bedrock roof/interior surface; do not bake Overworld assumptions into generic storage/job classes.

## 21. Tests and acceptance criteria

### 21.1 No chunk-generation regression

Create an integration test world with:

- generated chunks;
- holes never generated between/around them.

Run discovery, scheduling and snapshot/render preparation.

Assert:

- no new region chunk locations appear;
- no chunk ticket is created by map code;
- no absent chunk becomes FULL/generated;
- server loaded-chunk count does not increase because of map work.

Add a static architecture/source test rejecting forbidden APIs in `server.maprender`.

### 21.2 Deterministic environment

Render the same saved snapshot while the live server is independently changed between:

- day/night;
- clear/rain/thunder.

Result must remain identical for static-texture fixtures.

### 21.3 Entity contract

Fixture contains:

- zombie or another ordinary entity;
- player if practical;
- `ItemDisplay`;
- visible block entity.

Output/scene assertions:

- ordinary entities absent;
- `ItemDisplay` present;
- block entity present.

### 21.4 Tile seam/alignment test

Render adjacent tiles containing straight roads/stripes crossing tile boundaries. Compose them and assert exact world-coordinate alignment with no translation/rotation seam.

### 21.5 Readiness regression

Artificially delay section compilation/upload. Ensure the client does not publish the pre-ready framebuffer and eventually publishes only after the compile/upload barrier.

### 21.6 Suspicious frame rejection

Feed/produce a uniform black/grey/white result for a known non-empty fixture. It must fail validation and must not replace a valid cached tile.

### 21.7 Revision race

Dirty a tile while an earlier revision is in flight. Verify:

- old valid result may commit;
- dirty revision remains pending;
- a later job refreshes it.

### 21.8 Worker profile mismatch

Connect two volunteers with different resource-profile hashes. Assert a single cache namespace never receives jobs/results from both profiles.

### 21.9 Shader guard

With shader-compatible=false, the worker remains connected for normal Lostglade features but receives zero map jobs.

### 21.10 UI isolation

Rapid pan/zoom must not directly call snapshot/world/renderer APIs. It may only hit cache composition and demand tracking.

### 21.11 Full project regression

Every implementation milestone ends with at least:

```text
./gradlew compileJava compileClientJava test
./gradlew check
```

## 22. Observability

Expose debug counters/logging without spamming normal logs:

Server:

```text
existing chunks indexed
base tiles known
base tiles dirty
base tiles never rendered
jobs queued by lane
jobs in flight
workers eligible / rejected by reason
average snapshot ms
average queue age
average tile age
failure/backoff counts
```

Client debug status:

```text
map worker enabled
shader compatibility
profile hash prefix
current job
scene chunk count
compile queue
upload queue
prime/final render stage
last render ms
throttle reason
```

This should make a future black tile diagnosable from state, instead of requiring visual guessing.

## 23. Implementation sequence

Do not implement the entire system in one giant diff. Commit at the following boundaries when each stage is green.

### Phase 0 — contracts and guardrails

- create `server.maprender` / `client.maprender` packages;
- add `MapRenderProfile`, keys and metadata types;
- add no-chunk-loading architecture test;
- add negative-coordinate tile math tests;
- leave Yandex Maps UI as the current disabled shell.

**Exit:** no behaviour change, tests green.

### Phase 1 — read-only world index and tile store

- region-header existing-chunk index;
- base-tile discovery;
- source fingerprinting;
- versioned base tile store with atomic writes;
- startup comparison against existing metadata;
- no renderer protocol yet.

**Exit:** server can report exactly which existing terrain tiles need rendering without loading a chunk.

### Phase 2 — map worker capabilities and settings

- separate client map-render setting UI;
- server admission setting;
- shader guard;
- resource-profile fingerprint;
- worker registry + heartbeat/status;
- no scene/render jobs yet.

**Exit:** server can list eligible map workers and reasons rejected workers are ineligible.

### Phase 3 — read-only snapshot builder

- decode saved FULL chunks without registration in the world;
- preserve saved light;
- preserve block entities;
- read saved `ItemDisplay` data;
- build worker-patched vanilla chunk/entity packets;
- strict source/halo manifest.

**Exit:** snapshot fixture tests pass and no-chunk-generation integration test remains green.

### Phase 4 — isolated vanilla top-down client renderer

- fresh per-job map `ClientLevel`/`LevelRenderer`;
- orthographic projection;
- fixed environment;
- sky/cloud/weather/particle suppression;
- item-display-only entity filter;
- block entity rendering;
- readiness barrier;
- prime + final frame;
- result validation.

**Exit:** manual/test fixtures produce stable neighbouring top-down tiles with no black/grey/white corruption.

### Phase 5 — leases and distributed scheduler

- explicit job offer/accept/lease/cancel/result flow;
- weighted fair lanes;
- per-worker one-in-flight limit;
- client GPU budget arbiter;
- stale revision handling;
- worker/tile backoff.

**Exit:** dedicated renderer + multiple volunteers can process a queue without starving old work or impacting camera live streams.

### Phase 6 — base cache and zoom pyramid

- accept/atomically commit L0;
- derive L1+ on CPU;
- ancestor invalidation/rebuild;
- cache/profile migration namespace rules.

**Exit:** no GPU request contains a zoom/LOD level other than base render profile.

### Phase 7 — restore interactive Yandex Maps UI

- center/zoom state;
- tile compositor;
- stale/missing-tile display;
- GPS/markers overlay;
- demand hints only;
- frame composition caching/throttling.

**Exit:** aggressive pan/zoom creates no synchronous world-render work and does not change committed tile pixels.

### Phase 8 — hardening/performance

- long-running leak test;
- reconnect/timeout/cancel tests;
- large explored-world scan test;
- profile mismatch/resource reload tests;
- tune default worker budget and lane weights from metrics;
- only then consider safe scene reuse or animated-texture freezing if profiling/visual evidence justifies it.

## 24. Explicit fixes for old failure classes

| Old failure class | v2 prevention |
| --- | --- |
| Different zoom levels had different render/storage behaviour | only L0 is GPU-rendered; all zooms derive from L0 |
| Panning changed/recolored terrain pixels | world tiles are immutable world-coordinate assets; panning only composites them |
| Map work interfered with camera streams | separate service, protocol, shadow world and scheduler |
| Black/grey/white tiles were cached | compile/upload readiness barrier + prime frame + pathological-frame rejection |
| UI lagged while map work happened | UI never runs snapshot/GPU work; stale cache is always immediately usable |
| Risk of generating unexplored terrain | disk-only snapshot source + forbidden-API test + no tickets/getChunk path |
| Old job could overwrite newer state | snapshot/dirty revisions and atomic commit semantics |
| Different volunteer clients could make visually mismatched neighbours | canonical resource/render profile hash |
| A bad worker could cause retry storms | leases, worker health, exponential tile backoff |
| Scene state leaked between distant tiles | fresh map scene per job in v1 |

## 25. Recommended first implementation target

The first vertical slice should deliberately be small:

1. index saved Overworld chunks;
2. choose **one manually requested base tile**;
3. build its disk-only snapshot + halo;
4. send it only to the dedicated renderer;
5. create a fresh isolated map scene;
6. render vanilla orthographic 256x256 at 16 px/block (one chunk per canonical L0 tile);
7. validate and save one PNG;
8. stop.

Do not begin volunteer distribution, scheduler complexity, zoom pyramid or monitor UI until that single-tile pipeline passes:

- no-chunk-generation checks;
- block entity fixture;
- item display fixture;
- deterministic day/weather test;
- adjacent-tile seam test;
- black/white-frame rejection.

Once that foundation is correct, the rest of the system is orchestration rather than another rendering rewrite.
