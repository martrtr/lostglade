package com.lostglade.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;

public final class OrthodoxEyeCompositionTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets/lg2");

    public static void main(String[] args) throws Exception {
        require(OrthodoxAttackSystem.calculateEyeY(80.0D, 64) == 220.0D,
                "Surface observer eye height must follow the observer");
        require(OrthodoxAttackSystem.calculateEyeY(20.0D, 64) == 204.0D,
                "Underground observer eye height must follow the surface above the observer");
        for (int seed = 0; seed < 100; seed++) checkGeometry(seed);
        checkLengthDistribution();
        Set<String> models = new HashSet<>();
        models.add("orthodox_divine_eye");
        for (int i = 0; i < 16; i++) models.add("orthodox_eye_beam_" + i);
        for (int i = 0; i < 8; i++) models.add("orthodox_eye_wing_" + i);
        for (String name : models) checkAsset(name);
        checkWingDistribution();
        System.out.println("Divine eye: 64 rays, six root-pivoted wings, 100 layouts, 25 model assets and animation checks passed");
        for (String arg : args) {
            if (arg.equals("--preview")) OrthodoxEyePreview.render();
            if (arg.equals("--native-models")) {
                var parser = Class.forName("net.minecraft.client.renderer.block.model.BlockModel")
                        .getMethod("fromStream", java.io.Reader.class);
                for (String name : models) {
                    require(parser.invoke(null, new java.io.StringReader(model(name).toString())) != null,
                            "Native Minecraft parser rejected " + name);
                }
                System.out.println("Minecraft's native model parser accepted all 25 models");
            }
        }
    }

    private static void checkGeometry(long seed) throws Exception {
        OrthodoxEyeComposition c = new OrthodoxEyeComposition(seed);
        OrthodoxEyeComposition same = new OrthodoxEyeComposition(seed);
        Vector3f facing = c.transformation(0, 1, 1).getLeftRotation().transform(new Vector3f(0, 1, 0));
        near(facing, new Vector3f(0, -1, 0), "Eye must face ground");
        for (int tick = 0; tick <= 20; tick++) {
            var eye = c.transformation(0, 1, tick / 20F);
            Vector3f width = eye.getMatrix().transformDirection(new Vector3f(0, 0, 1));
            float eyeScale = 16F;
            require(Math.abs(Math.abs(width.x) - eyeScale) < 0.001, "Eye must retain full horizontal width while opening");
            require(Math.abs(eye.getScale().x() - Math.max(0.0001F, eyeScale * OrthodoxEyeComposition.smooth(tick / 20F))) < 0.001,
                    "Only the eye's short axis opens from a slit");
        }
        for (int part = 0; part < OrthodoxEyeComposition.PART_COUNT; part++) {
            require(c.model(part).equals(same.model(part)), "Layout must be identical for all viewers");
            require(c.transformation(part, 1, 1).getMatrix().equals(same.transformation(part, 1, 1).getMatrix()),
                    "Placement and flip must be identical for all viewers");
            for (int tick = 0; tick <= 20; tick++) {
                float progress = tick / 20.0F;
                Transformation t = c.transformation(part, 1, progress);
                require(t.getMatrix().isFinite(), "Nonfinite animation matrix");
                require(t.getScale().x() > 0 && t.getScale().y() > 0 && t.getScale().z() > 0, "Singular animation");
                near(c.transformation(part, 0.5F, progress).getTranslation(),
                        t.getTranslation(), "Visibility must not move ray roots");
            }
        }
        for (int ray = 0; ray < OrthodoxEyeComposition.RAY_COUNT; ray++) {
            Transformation t = c.transformation(ray + 1, 1, 1);
            require(Math.abs(t.getScale().x() - 16) < 0.001
                            && Math.abs(t.getScale().y() - 32) < 0.001
                            && Math.abs(t.getScale().z() - 16) < 0.001,
                    "Rays must be doubled on every axis while restoring authored half-height");
            Vector3f root = t.getMatrix().transformPosition(new Vector3f());
            near(root, c.rayPoint(ray, 0, 1), "Ray root pivot");
            require(Math.abs(root.length() - 14) < 0.001, "Ray roots must form radius-14 circle");
            Vector3f front = t.getLeftRotation().transform(new Vector3f(0, 0, -1));
            near(front, new Vector3f(0, -1, 0), "Ray front must face observed player");
            float maxY = 8;
            for (var value : model(c.model(ray + 1)).getAsJsonArray("elements")) {
                JsonObject e = value.getAsJsonObject();
                require(e.getAsJsonArray("from").get(1).getAsFloat() >= 8, "Geometry extends behind root");
                maxY = Math.max(maxY, e.getAsJsonArray("to").get(1).getAsFloat());
            }
            Vector3f tip = t.getMatrix().transformPosition(new Vector3f(0, (maxY - 8) / 16, 0));
            near(tip, c.rayPoint(ray, 1, 1), "Ray tip must match runtime authored length");
            for (int tick = 3; tick <= 20; tick++) {
                float p = tick / 20.0F;
                Transformation growing = c.transformation(ray + 1, 1, p);
                Vector3f end = growing.getMatrix().transformPosition(new Vector3f(0, (maxY - 8) / 16, 0));
                near(growing.getTranslation(), root, "Growing ray must stay attached to its final root");
                near(end, c.rayPoint(ray, OrthodoxEyeComposition.rayGrowth(p), 1), "Growing tip");
            }
        }
        Set<String> wings = new HashSet<>();
        float previousAngle = 0;
        for (int wing = 0; wing < OrthodoxEyeComposition.WING_COUNT; wing++) {
            int part = 1 + OrthodoxEyeComposition.RAY_COUNT + wing;
            require(wings.add(c.model(part)), "Six distinct wing variants per composition");
            Transformation t = c.transformation(part, 1, 1);
            Vector3f root = new Vector3f(t.getTranslation());
            Vector3f radial = new Vector3f(root.x, 0, root.z);
            var rendered = new Matrix4f(t.getMatrix()).rotateY((float) Math.PI);
            Vector3f direction = rendered.transformDirection(new Vector3f(1, 0, 0)).normalize();
            near(direction, new Vector3f(radial).normalize(), "Wing length must point away from center");
            Vector3f tip = rendered.transformPosition(new Vector3f(1, 0, 0));
            require(tip.length() > root.length(), "Rendered feather tips must extend away from the rays");
            require(Math.abs(t.getLeftRotation().transform(new Vector3f(0, 0, 1)).y) > 0.999,
                    "One of the two eye faces must face ground");
            require(Math.abs(radial.length() - c.rayPoint(0, 0, 1).length()) < 0.002,
                    "Wing roots must share the ray-start radius");
            require(Math.abs(t.getScale().x() - 80.0F / 1.5F) < 0.001, "Wings must be reduced by a factor of 1.5");
            float eyeY = c.transformation(0, 1, 1).getTranslation().y();
            require(c.rayPoint(0, 0, 1).y < eyeY && eyeY < root.y,
                    "Eye must sit above rays and below wings");
            if (seed < 2) require(lowestRenderedY(c.model(part), rendered) > 0.5F,
                    "Tilted feathers must stay above the ray geometry");
            float angle = (float) Math.atan2(root.z, root.x);
            if (wing > 0) {
                double delta = (angle - previousAngle + Math.PI * 2) % (Math.PI * 2);
                require(Math.abs(delta - Math.PI / 3) <= 0.141, "Near-even wing spacing with slight jitter");
            }
            previousAngle = angle;
            for (int tick = 0; tick <= 20; tick++) {
                Transformation growing = c.transformation(part, 1, tick / 20F);
                near(growing.getMatrix().transformPosition(new Vector3f()), root, "Wing grows about its root");
                require(growing.getScale().x() == growing.getScale().y()
                                && growing.getScale().y() == growing.getScale().z(), "Uniform wing growth");
            }
        }
    }

    private static float lowestRenderedY(String name, Matrix4f rendered) throws Exception {
        float lowest = Float.POSITIVE_INFINITY;
        for (var value : model(name).getAsJsonArray("elements")) {
            JsonObject e = value.getAsJsonObject();
            Quaternionf rotation = new Quaternionf();
            Vector3f origin = new Vector3f();
            if (e.has("rotation")) {
                JsonObject r = e.getAsJsonObject("rotation");
                var pivot = r.getAsJsonArray("origin");
                origin.set(pivot.get(0).getAsFloat(), pivot.get(1).getAsFloat(), pivot.get(2).getAsFloat());
                rotation.rotationZYX((float) Math.toRadians(r.get("z").getAsFloat()),
                        (float) Math.toRadians(r.get("y").getAsFloat()),
                        (float) Math.toRadians(r.get("x").getAsFloat()));
            }
            for (int corner = 0; corner < 8; corner++) {
                Vector3f vertex = new Vector3f();
                for (int axis = 0; axis < 3; axis++) {
                    vertex.setComponent(axis, e.getAsJsonArray((corner & (1 << axis)) == 0 ? "from" : "to")
                            .get(axis).getAsFloat());
                }
                vertex.sub(origin).rotate(rotation).add(origin).sub(8, 8, 8).div(16);
                lowest = Math.min(lowest, rendered.transformPosition(vertex).y);
            }
        }
        return lowest;
    }

    private static void checkWingDistribution() {
        require(OrthodoxEyeComposition.WING_COUNT == 6 && OrthodoxEyeComposition.PART_COUNT == 71, "Six wings per gaze");
        Set<String> variants = new HashSet<>();
        int flipped = 0;
        for (int seed = 0; seed < 1000; seed++) {
            var c = new OrthodoxEyeComposition(seed);
            for (int part = 65; part < 71; part++) {
                variants.add(c.model(part));
                if (c.transformation(part, 1, 1).getLeftRotation().transform(new Vector3f(0, 0, 1)).y > 0) flipped++;
            }
        }
        require(variants.size() == 8, "All eight authored wings must appear");
        require(flipped > 2800 && flipped < 3200, "Wing flips must be 50/50");
    }

    private static void checkLengthDistribution() {
        require(OrthodoxEyeComposition.RAY_COUNT == 64, "Twice as many rays");
        double[] sums = new double[4];
        int[] counts = new int[4];
        Set<String> variants = new HashSet<>();
        Set<Float> axialLengths = new HashSet<>();
        for (int seed = 0; seed < 1000; seed++) {
            OrthodoxEyeComposition composition = new OrthodoxEyeComposition(seed);
            for (int ray = 0; ray < OrthodoxEyeComposition.RAY_COUNT; ray++) {
                Vector3f root = composition.rayPoint(ray, 0, 1);
                double angle = Math.atan2(Math.abs(root.z), Math.abs(root.x));
                double distance = Math.min(angle, Math.PI / 2 - angle);
                int band = Math.min(3, (int) (distance / (Math.PI / 16)));
                float length = composition.rayLength(ray);
                sums[band] += length;
                counts[band]++;
                variants.add(composition.model(ray + 1));
                if (band == 0) axialLengths.add(length);
            }
        }
        double[] mean = new double[4];
        for (int band = 0; band < 4; band++) {
            require(counts[band] > 0, "Every angular band is sampled");
            mean[band] = sums[band] / counts[band];
        }
        require(mean[0] > mean[1] && mean[1] > mean[2] && mean[2] > mean[3],
                "Long rays must become less likely toward diagonals");
        require(mean[0] - mean[1] > mean[2] - mean[3], "Preference must change fastest near axes");
        require(mean[0] - mean[3] > 8, "The four-point silhouette must be pronounced");
        require(variants.size() == 16 && axialLengths.size() > 4, "Lengths must remain random, not fixed by angle");
    }

    static JsonObject model(String name) throws Exception {
        return JsonParser.parseString(Files.readString(ASSETS.resolve("models/item/" + name + ".json"))).getAsJsonObject();
    }

    private static void checkAsset(String name) throws Exception {
        JsonObject json = model(name);
        require(!json.get("ambientocclusion").getAsBoolean(), "Ambient occlusion darkens eye");
        var item = JsonParser.parseString(Files.readString(ASSETS.resolve("items/" + name + ".json"))).getAsJsonObject();
        require(item.getAsJsonObject("model").get("model").getAsString().equals("lg2:item/" + name), "Broken item model reference");
        JsonObject textures = json.getAsJsonObject("textures");
        for (var texture : textures.entrySet()) {
            String location = texture.getValue().getAsString().replace("lg2:", "");
            require(Files.exists(ASSETS.resolve("textures/" + location + ".png")), "Missing texture " + location);
            if (name.startsWith("orthodox_eye_wing_")) {
                var image = ImageIO.read(ASSETS.resolve("textures/" + location + ".png").toFile());
                require(image.getWidth() == 64 && image.getHeight() == 64, "Preserve the supplied pixel-grid texture");
            }
        }
        for (var value : json.getAsJsonArray("elements")) {
            JsonObject e = value.getAsJsonObject();
            require(!e.get("shade").getAsBoolean(), "Directional face shade");
            for (int axis = 0; axis < 3; axis++) {
                float min = e.getAsJsonArray("from").get(axis).getAsFloat();
                float max = e.getAsJsonArray("to").get(axis).getAsFloat();
                require(min >= -16 && max <= 32 && min < max, "Invalid model bounds " + name);
                if (name.startsWith("orthodox_eye_wing_")) {
                    require(min * 4 == Math.round(min * 4) && max * 4 == Math.round(max * 4),
                            "Preserve authored integer geometry before export normalization");
                }
            }
            for (var face : e.getAsJsonObject("faces").entrySet()) {
                String texture = face.getValue().getAsJsonObject().get("texture").getAsString();
                require(textures.has(texture.substring(1)), "Undefined UV texture " + name);
                for (var coordinate : face.getValue().getAsJsonObject().getAsJsonArray("uv")) {
                    require(coordinate.getAsFloat() >= 0 && coordinate.getAsFloat() <= 16, "UV outside texture " + name);
                }
            }
        }
        if (name.startsWith("orthodox_eye_wing_")) {
            require(json.getAsJsonArray("elements").size() >= 450, "Do not omit hidden feather groups");
        }
    }

    private static void near(Vector3fc a, Vector3fc b, String message) { require(a.distance(b) < 0.002, message + ": " + a + " != " + b); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
