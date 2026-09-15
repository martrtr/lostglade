# Divine Gaze Assets

Only the Orthodox attack visuals are replaced. Defense wings, gaze duration,
punishment, targeting, sounds, terrain-relative height and visibility rules are
unchanged.

`build_divine_eye_assets.ps1` imports `center_eye.json/png` and `beams.json/png`
from the supplied directory and generates eight solid wing variants.
Run it from PowerShell; `-InputDirectory` overrides the import directory.
The checked-in outputs do not require the original Downloads files at runtime.

Outputs are under `mods/lg2-0.1.0/src/main/resources/assets/lg2/`:

- `models/item/orthodox_divine_eye.json`: supplied center, recentered at 8,8,8.
- `models/item/orthodox_eye_beam_0.json` through `_15.json`: individual ray groups.
- Wing assets remain staged for later work, but are not part of the active composition.
- Matching `items/` definitions and `textures/item/` images.

Ray model coordinates are halved at export to stay inside vanilla model bounds.
The runtime uses scale 8/16/8, restoring the export normalization and doubling
the author's local Y length relative to X/Z. Each ray's pivot is its bottom
center. Composition selection is seeded once per gaze, not once per viewer.

The new wing atlas is
`textures/item/orthodox_eye_wing_atlas.png`, generated using the built-in
image generation tool. No image-generation service is used by the server.

Generation prompt:

> Create one square opaque Minecraft pixel-art texture atlas for a volumetric
> angel wing 3D model. Strict 2 by 2 equal quadrants with no borders and no text.
> Top left quadrant: ivory-white feather surface, delicate parallel pale cream
> barbs flowing vertically and central fine golden shaft, fills entire quadrant.
> Top right quadrant: darker warm champagne/gold feather underside, parallel
> barbs vertically, fills entire quadrant. Bottom left quadrant: one luminous
> amber angel eye seen straight on, almond-shaped creamy sclera, detailed
> concentric amber iris and small black round pupil, pale ivory background,
> eye centered and fills quadrant with small margin. Bottom right quadrant:
> one different icy pale blue angel eye straight on, silver ivory sclera blue
> iris small dark pupil, pale ivory background. Crisp pixel-art texels, no 3D
> rendering, no cast shadows, no scene, no labels, no wing silhouette, full
> bleed texture tiles only. This is a UV material atlas, not illustration.
> 1024x1024.

From the mod directory:

```powershell
.\gradlew.bat runOrthodoxEyeCompositionTest prepareDevResourcePack --no-daemon
```

The standalone test checks 100 seeded layouts, model/texture references, legal
element bounds, 32 displayed rays from two complete shuffled model sets, doubled
ray length, radius-14 circular root pivots, downward orientation
and animation matrices. It also renders diagnostic orthographic
previews to `build/reports/divine-eye/`. These are asset previews, not screenshots
from Minecraft; in-game interpolation and appearance still need client testing.
