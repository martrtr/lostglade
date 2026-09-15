package com.lostglade.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;

public final class OrthodoxWingRigTest {
    public static void main(String[] args) throws Exception {
        var closed = OrthodoxWingRig.sample(0, 0);
        for (int tick = 0; tick < 200; tick++) {
            var staticPose = OrthodoxWingRig.sample(tick / 20F, 0);
            for (int bone = 0; bone < 12; bone++) same(closed[bone], staticPose[bone], "Folded wings must be static");
        }
        var root = closed[0];
        near(root.position(), new Vector3f(-0.11F, 1.173F, 0.115F), "Back attachment");
        require(Math.abs(OrthodoxWingRig.SCALE - 1F) < 0.00001F, "Original wing scale");
        var rootRotation = new Quaternionf().rotationZYX((float) Math.toRadians(11.1952808888),
                (float) Math.toRadians(-56.1243117307), (float) Math.toRadians(7.5768241069));
        require(Math.abs(root.rotation().dot(rootRotation)) > 0.99999F, "Original Figura rotation convention");
        // Part 3's authored closed pose shifts it by -2 pixels, in its parent's coordinate system.
        Vector3f joint = closed[1].rotation().transform(new Vector3f(0, 0, 3F / 16 * OrthodoxWingRig.SCALE)).add(closed[1].position());
        near(joint, closed[2].position(), "Folded position keyframe must not be dropped");
        for (float blend : new float[]{0, 0.25F, 0.5F, 0.75F, 1}) {
            for (int tick = 0; tick <= 160; tick++) {
                var poses = OrthodoxWingRig.sample(tick / 80F, blend);
                var repeated = OrthodoxWingRig.sample(tick / 80F + 2, blend);
                for (int bone = 0; bone < 12; bone++) {
                    require(poses[bone].position().isFinite() && poses[bone].rotation().isFinite(), "Finite pose");
                    same(poses[bone], repeated[bone], "Continuous 2-second cycle");
                    require(poses[bone].position().distance(root.position()) < 4, "No detached bones");
                }
                // The source idle pose intentionally offsets right part 4 by one extra pixel.
                for (int bone = 0; bone < (blend == 1 ? 6 : 3); bone++) {
                    var left = poses[bone]; var right = poses[bone + 6];
                    near(new Vector3f(left.position()).mul(-1, 1, 1), right.position(), "Mirrored attachment chain");
                }
            }
        }
        var animation = new OrthodoxWingRig.Animation();
        for (int i = 0; i < 20; i++) animation.tick(true);
        for (int i = 0; i < 10; i++) animation.tick(false);
        var settled = animation.tick(false);
        for (int i = 0; i < 12; i++) same(closed[i], settled[i], "Landing restores static pose");
        for (int tick = 0; tick < 100; tick++) {
            require(animation.tick(false) == settled, "Stationary animation must reuse the frozen pose");
        }
        // Include the actual vanilla ItemDisplayRenderer Y rotation in the rendered result.
        // Check all corners, not just joint origins: the old bug left origins correct but reversed meshes.
        for (int tick = 0; tick <= 40; tick++) {
            var poses = OrthodoxWingRig.sample(tick / 20F, tick == 0 ? 0 : 1);
            for (int bone = 0; bone < 12; bone++) {
                var pose = poses[bone];
                var transform = OrthodoxWingRig.displayTransformation(pose, 1.8F);
                var rendered = new org.joml.Matrix4f().translation(0, 1.8F, 0)
                        .mul(transform.getMatrix()).rotateY((float) Math.PI);
                String name = "orthodox_angel_wing_" + (bone < 6 ? "left" : "right") + "_" + (bone % 6 + 1);
                var cube = model(name).getAsJsonArray("elements").get(0).getAsJsonObject();
                for (int corner = 0; corner < 8; corner++) {
                    Vector3f local = new Vector3f();
                    for (int axis = 0; axis < 3; axis++) {
                        String extent = (corner & (1 << axis)) == 0 ? "from" : "to";
                        local.setComponent(axis, (cube.getAsJsonArray(extent).get(axis).getAsFloat() - 8) / 16);
                    }
                    Vector3f expected = new Vector3f(local).mul(OrthodoxWingRig.SCALE)
                            .rotate(pose.rotation()).add(pose.position());
                    near(rendered.transformPosition(local), expected, "Renderer must preserve bone-local geometry");
                }
            }
        }
        var before = OrthodoxWingRig.sample(1.99999F, 1);
        var after = OrthodoxWingRig.sample(0.00001F, 1);
        for (int i = 0; i < 12; i++) near(before[i].position(), after[i].position(), "Loop seam");
        for (String side : new String[]{"left", "right"}) {
            for (int part = 1; part <= 6; part++) {
                String name = "orthodox_angel_wing_" + side + "_" + part;
                JsonObject model = model(name);
                require(model.getAsJsonArray("elements").size() == 1, "One authored cube per bone");
                var cube = model.getAsJsonArray("elements").get(0).getAsJsonObject();
                for (String extent : new String[]{"from", "to"}) {
                    for (var value : cube.getAsJsonArray(extent)) {
                        require(value.getAsFloat() >= -16 && value.getAsFloat() <= 32, "Legal vanilla model bounds");
                    }
                }
                require(cube.getAsJsonObject("faces").size() == 2, "Both textured sides");
                String item = Files.readString(Path.of("src/main/resources/assets/lg2/items/" + name + ".json"));
                require(item.contains("lg2:item/" + name), "Valid item model reference");
            }
        }
        var texture = ImageIO.read(Path.of("src/main/resources/assets/lg2/textures/item/orthodox_angel_wings.png").toFile());
        require(texture.getWidth() == 64 && texture.getHeight() == 64, "Original wing atlas");
        OrthodoxWingPreview.render();
        System.out.println("Wing rig: 12 assets, source pivots/position keys, static idle, fly loop, symmetry and landing checks passed");
    }

    static JsonObject model(String name) throws Exception {
        return JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/lg2/models/item/" + name + ".json"))).getAsJsonObject();
    }

    private static void same(OrthodoxWingRig.Pose a, OrthodoxWingRig.Pose b, String message) {
        near(a.position(), b.position(), message);
        require(Math.abs(a.rotation().dot(b.rotation())) > 0.99999F, message);
    }

    private static void near(Vector3f a, Vector3f b, String message) {
        require(a.distance(b) < 0.0003F, message + ": " + a + " != " + b);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
