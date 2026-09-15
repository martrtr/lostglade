package com.lostglade.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.Transformation;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class OrthodoxEyeCompositionTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets/lg2");

    public static void main(String[] args) throws Exception {
        for (int seed = 0; seed < 100; seed++) checkGeometry(seed);
        checkLengthDistribution();
        Set<String> models = new HashSet<>();
        models.add("orthodox_divine_eye");
        for (int i = 0; i < 16; i++) models.add("orthodox_eye_beam_" + i);
        for (String name : models) checkAsset(name);
        System.out.println("Divine eye: 64 rays, axis-weighted random lengths, 100 layouts, 17 model assets and animation checks passed");
        if (args.length > 0 && args[0].equals("--preview")) OrthodoxEyePreview.render();
    }

    private static void checkGeometry(long seed) throws Exception {
        OrthodoxEyeComposition c = new OrthodoxEyeComposition(seed);
        OrthodoxEyeComposition same = new OrthodoxEyeComposition(seed);
        Vector3f facing = c.transformation(0, 1, 1).getLeftRotation().transform(new Vector3f(0, 1, 0));
        near(facing, new Vector3f(0, -1, 0), "Eye must face ground");
        for (int tick = 0; tick <= 20; tick++) {
            var eye = c.transformation(0, 1, tick / 20F);
            Vector3f width = eye.getMatrix().transformDirection(new Vector3f(0, 0, 1));
            float eyeScale = 16F / 1.5F;
            require(Math.abs(Math.abs(width.x) - eyeScale) < 0.001, "Eye must retain full horizontal width while opening");
            require(Math.abs(eye.getScale().x() - Math.max(0.0001F, eyeScale * OrthodoxEyeComposition.smooth(tick / 20F))) < 0.001,
                    "Only the eye's short axis opens from a slit");
        }
        for (int part = 0; part < OrthodoxEyeComposition.PART_COUNT; part++) {
            require(c.model(part).equals(same.model(part)), "Layout must be identical for all viewers");
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
        }
        for (var value : json.getAsJsonArray("elements")) {
            JsonObject e = value.getAsJsonObject();
            require(!e.get("shade").getAsBoolean(), "Directional face shade");
            for (int axis = 0; axis < 3; axis++) {
                float min = e.getAsJsonArray("from").get(axis).getAsFloat();
                float max = e.getAsJsonArray("to").get(axis).getAsFloat();
                require(min >= -16 && max <= 32 && min < max, "Invalid model bounds " + name);
            }
            for (var face : e.getAsJsonObject("faces").entrySet()) {
                String texture = face.getValue().getAsJsonObject().get("texture").getAsString();
                require(textures.has(texture.substring(1)), "Undefined UV texture " + name);
            }
        }
    }

    private static void near(Vector3fc a, Vector3fc b, String message) { require(a.distance(b) < 0.002, message + ": " + a + " != " + b); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
