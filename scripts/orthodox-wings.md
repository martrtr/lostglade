# Orthodox defense wings

`import_orthodox_wings.ps1` imports the original Snoodles Barn Owl Wings Base
Figura archive, without editing its embedded `barn_owl_wing_txt` texture.
Run the script from PowerShell; `-Archive` overrides its default source path.

- Only the two six-bone wing chains are exported. The hidden player model and
  texture-editing reference plane are not attachment geometry.
- Every cube is centered on its own bone pivot (the vanilla item origin is
  `[8,8,8]`). Atlas UVs are converted from 64 pixels to vanilla's 16-unit UV space.
- `lg2/orthodox_wing_rig.json` stores the original pivots, idle rotation/position
  at time zero, and all five rotation keys per bone in the two-second fly loop.
- `idle` is frozen, not looped. Flight uses `fly`, with a six-tick local-pose
  transition. The idle pose has an authored one-pixel asymmetry in right part 4.
- Joint offsets are transformed by the parent joint, not rotated independently
  around the player. All twelve item displays are direct player passengers via
  the same accessor attachment mechanism used by the copper jetpack.
- Source model coordinates are Y-up, Z-back. No extra root X rotation is applied.
  The attachment root is 1.22 blocks above the feet, 0.27 blocks behind the body;
  left/right roots are spread to -0.20/+0.20 blocks. Meshes and all joint offsets
  are uniformly scaled by 2/3 around these roots.
- The runtime compensates the passenger seat height before applying bone poses.
  Flying state does not alter movement, invulnerability or interaction rules.
- The display's right rotation cancels the vanilla ItemDisplayRenderer Y
  half-turn. This is a model-space correction, not an extra bone rotation.
- Unchanged transforms do not restart client interpolation. The settled idle
  pose is cached; changing a passenger clears its last-sent transform.

The animation axis conversion follows Figura's
[animRotation/animPosition implementation](https://github.com/FiguraMC/Figura/blob/1.20/common/src/main/java/org/figuramc/figura/model/FiguraModelPart.java):
negate X/Y rotation and X position. Geometry/pivots are not negated.

Verification: from `mods/lg2-0.1.0`, run
`./gradlew runOrthodoxWingRigTest prepareDevResourcePack`.
The test validates assets, pivots, folded offsets, static idle, flight loop and
landing transitions. Orthographic asset previews go to
`build/reports/orthodox-wings/`; these do not replace an in-game visual check.
