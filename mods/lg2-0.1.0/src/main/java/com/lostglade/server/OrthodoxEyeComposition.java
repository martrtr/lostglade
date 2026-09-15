package com.lostglade.server;

import com.mojang.math.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Random;

/** Geometry shared by all viewers of one gaze; randomness never changes on a frame update. */
final class OrthodoxEyeComposition {
    static final int RAY_COUNT = 64;
    static final int PART_COUNT = 1 + RAY_COUNT;
    private static final float[] AUTHORED_RAY_HEIGHTS = {37, 36, 35, 34, 31, 30, 29, 28, 25, 24, 23, 22, 19, 18, 17, 16};
    private static final float EYE_SCALE = 16.0F / 1.5F;
    // Export divides ray geometry by two to fit the vanilla model coordinate limits.
    // X/Z restore that export scale; Y also restores the author's half-height rays.
    private static final float RAY_SCALE = 16.0F;
    private static final float EYE_OUTER_RADIUS = 12.0F;
    private static final float RAY_ROOT_GAP = 2.0F;
    private final int[] rayVariants = new int[RAY_COUNT];
    private final float phase;

    OrthodoxEyeComposition(long seed) {
        Random random = new Random(seed);
        phase = random.nextFloat() * (float) (Math.PI * 2.0);
        for (int ray = 0; ray < RAY_COUNT; ray++) {
            rayVariants[ray] = sampleRayVariant(random, angle(ray));
        }
    }

    private static int sampleRayVariant(Random random, float angle) {
        double axisDistance = Math.atan2(Math.min(Math.abs(Math.sin(angle)), Math.abs(Math.cos(angle))),
                Math.max(Math.abs(Math.sin(angle)), Math.abs(Math.cos(angle)))) / (Math.PI / 4);
        // Squared proximity steepens the preference near world X/Z axes, without fixing any ray length.
        double preferredLength = Math.pow(1 - axisDistance, 2);
        double[] weights = new double[AUTHORED_RAY_HEIGHTS.length];
        double total = 0;
        float longest = AUTHORED_RAY_HEIGHTS[0];
        float shortest = AUTHORED_RAY_HEIGHTS[AUTHORED_RAY_HEIGHTS.length - 1];
        for (int variant = 0; variant < weights.length; variant++) {
            double length = (AUTHORED_RAY_HEIGHTS[variant] - shortest) / (longest - shortest);
            double deviation = (length - preferredLength) / 0.16;
            weights[variant] = 0.002 + Math.exp(-0.5 * deviation * deviation);
            total += weights[variant];
        }
        double roll = random.nextDouble() * total;
        for (int variant = 0; variant < weights.length; variant++) {
            roll -= weights[variant];
            if (roll <= 0) return variant;
        }
        return weights.length - 1;
    }

    String model(int part) {
        if (part == 0) return "orthodox_divine_eye";
        return "orthodox_eye_beam_" + rayVariants[part - 1];
    }

    Transformation transformation(int part, float proximity, float open) {
        float visible = clamp(proximity);
        float opening = smooth(open);
        if (part == 0) {
            // Authored +Y faces the sky. Turn it toward the ground, long axis along X.
            return transform(new Vector3f(), new Quaternionf().rotateY((float) Math.PI / 2).rotateX((float) Math.PI),
                    EYE_SCALE * visible * opening, EYE_SCALE * visible, EYE_SCALE * visible);
        }
        int ray = part - 1;
        float growth = rayGrowth(open);
        return transform(rayPoint(ray, 0, visible), rayRotation(ray),
                RAY_SCALE * visible * growth, RAY_SCALE * 2 * visible * growth, RAY_SCALE * visible * growth);
    }

    private static Transformation transform(Vector3f origin, Quaternionf rotation, float x, float y, float z) {
        return new Transformation(origin, rotation,
                new Vector3f(Math.max(0.0001F, x), Math.max(0.0001F, y), Math.max(0.0001F, z)), new Quaternionf());
    }

    private float angle(int ray) { return phase + ray * (float) (Math.PI * 2 / RAY_COUNT); }

    private Quaternionf rayRotation(int ray) {
        // Local +Y points radially outward while local +Z, the authored front,
        // points down toward the observed player.
        return new Quaternionf()
                .rotateY((float) Math.PI / 2 - angle(ray))
                .rotateX((float) Math.PI / 2)
                // The authored visible face is north (-Z). Flip it around the
                // model's own length axis after laying the beam horizontally.
                .rotateY((float) Math.PI);
    }

    Vector3f rayPoint(int ray, float fraction, float visible) {
        float dx = (float) Math.cos(angle(ray));
        float dz = (float) Math.sin(angle(ray));
        // All roots form one circle beyond the eye's furthest points, rather than
        // following its elliptical edge.
        // Opening and visibility affect the beam, never the position of its root.
        float radius = EYE_OUTER_RADIUS + RAY_ROOT_GAP + rayLength(ray) * fraction * visible;
        return new Vector3f(dx * radius, 0, dz * radius);
    }

    float rayLength(int ray) { return AUTHORED_RAY_HEIGHTS[rayVariants[ray]] / 32.0F * RAY_SCALE * 2; }
    static float rayGrowth(float open) { return smooth((open - 0.12F) / 0.88F); }
    static float smooth(float value) { float t = clamp(value); return t * t * (3 - 2 * t); }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
}
