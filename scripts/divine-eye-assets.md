# Divine Gaze Assets

The composition contains the central eye, 64 rays and six volumetric seraph wings.
Only Orthodox attack visuals are changed; defense wings and attack mechanics are
independent.

`import_divine_eye_composition.ps1` imports replacement assets without touching the
central eye. Parameters override these default inputs:

- `-BeamModel`: `Downloads/Telegram Desktop/beams (4).json`.
- `-BeamTexture`: `Downloads/Telegram Desktop/beams (2).png`.
- `-WingModel`: `Downloads/orthodox_seraph_wings_v2_optimized.bbmodel`.
- `-WingTexture`: `Downloads/orthodox_seraph_wings_v2.png`.

`build_divine_eye_assets.ps1` imports `center_eye.json/png` from `-InputDirectory`,
then delegates ray/wing import to this script. Checked-in assets do not need the
Downloads files at runtime.

Outputs under `mods/lg2-0.1.0/src/main/resources/assets/lg2/`:

- `models/item/orthodox_divine_eye.json`: center, pivot at 8,8,8.
- `models/item/orthodox_eye_beam_0.json` through `_15.json`: ray length variants.
- `models/item/orthodox_eye_wing_0.json` through `_7.json`: the eight root folders.
- Matching `items/` definitions and `textures/item/` images.

Ray coordinates are halved at export to fit vanilla model bounds. Runtime scales
16/32/16 restore export normalization and the author's half-height. Roots are
fixed on the radius-14 circle; axis-weighted random length selection is unchanged.

Wing coordinates and pivots are divided by four and recentered at 8,8,8. Runtime
scale 40 gives each authored unit 0.625 blocks. Native Minecraft 1.21.11 XYZ cube
rotations preserve layered feathers, using Z-Y-X rotation composition. UVs are
converted from the supplied 64x64 pixel atlas without resampling the texture.
Hidden editor groups are exported, not discarded.

Each gaze chooses six different wings, roughly evenly spaced with slight angular
jitter. Roots sit 0.6 blocks beyond the longest nearby ray tip. Each wing has a
50/50 local length-axis flip; both authored eye faces are retained. Uniform growth
is around the root; closing reverses that animation. Choices are seeded once per
gaze and shared by viewers. Existing full-bright lighting and per-viewer eye
position/visibility rules apply to all parts.

From the mod directory:

```powershell
.\gradlew.bat runOrthodoxEyeCompositionTest prepareDevResourcePack
```

Tests check seeded layouts, ray length probabilities, root pivots, wing direction,
spacing, flips, animation, all 25 assets and texture/UV references. A diagnostic
orthographic render is written to `build/reports/divine-eye/composition.png`.
This is an asset preview, not a Minecraft screenshot; client interpolation and
appearance still need in-game testing.
