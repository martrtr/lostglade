package com.lostglade.server;

import com.mojang.math.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Random;

/** Geometry shared by all viewers of one gaze; randomness never changes on a frame update. */
final class OrthodoxEyeComposition {
    static final int RAY_COUNT = 64;
    static final int WING_COUNT = 6;
    static final int PART_COUNT = 1 + RAY_COUNT + WING_COUNT;
    private static final float[] AUTHORED_RAY_HEIGHTS = {37, 36, 35, 34, 31, 30, 29, 28, 25, 24, 23, 22, 19, 18, 17, 16};
    private static final float EYE_SCALE = 16.0F;
    // Export divides ray geometry by two to fit the vanilla model coordinate limits.
    // X/Z restore that export scale; Y also restores the author's half-height rays.
    private static final float RAY_SCALE = 16.0F;
    private static final float EYE_OUTER_RADIUS = 12.0F;
    private static final float RAY_ROOT_GAP = 2.0F;
    private static final float RAY_ROOT_RADIUS = EYE_OUTER_RADIUS + RAY_ROOT_GAP;
    private static final float EYE_HEIGHT_OFFSET = 2.0F;
    // Keep the established layer separation even when wing size changes.
    private static final float WING_HEIGHT_OFFSET = 6.5F;
    private static final float WING_SCALE = 80.0F / 1.5F;
    private final int[] rayVariants = new int[RAY_COUNT];
    private final int[] wingVariants = new int[WING_COUNT];
    private final float[] wingAngles = new float[WING_COUNT];
    private final boolean[] wingFlips = new boolean[WING_COUNT];
    private final float phase;

    OrthodoxEyeComposition(long seed) {
        Random random = new Random(seed);
        phase = random.nextFloat() * (float) (Math.PI * 2.0);
        for (int ray = 0; ray < RAY_COUNT; ray++) {
            rayVariants[ray] = sampleRayVariant(random, angle(ray));
        }
        int[] variants = {0, 1, 2, 3, 4, 5, 6, 7};
        for (int wing = 0; wing < WING_COUNT; wing++) {
            int pick = wing + random.nextInt(variants.length - wing);
            int swap = variants[wing];
            variants[wing] = variants[pick];
            variants[pick] = swap;
            wingVariants[wing] = variants[wing];
            wingAngles[wing] = phase + wing * (float) (Math.PI * 2 / WING_COUNT)
                    + (random.nextFloat() - 0.5F) * 0.14F;
            wingFlips[wing] = random.nextBoolean();
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
        if (part <= RAY_COUNT) return "orthodox_eye_beam_" + rayVariants[part - 1];
        return "orthodox_eye_wing_" + wingVariants[part - 1 - RAY_COUNT];
    }

    Transformation transformation(int part, float proximity, float open) {
        float visible = clamp(proximity);
        float opening = smooth(open);
        if (part == 0) {
            // Authored +Y faces the sky. Turn it toward the ground, long axis along X.
            return transform(new Vector3f(0, EYE_HEIGHT_OFFSET, 0), new Quaternionf().rotateY((float) Math.PI / 2).rotateX((float) Math.PI),
                    EYE_SCALE * visible * opening, EYE_SCALE * visible, EYE_SCALE * visible);
        }
        if (part > RAY_COUNT) {
            int wing = part - 1 - RAY_COUNT;
            float angle = wingAngles[wing];
            Vector3f root = new Vector3f((float) Math.cos(angle) * RAY_ROOT_RADIUS, WING_HEIGHT_OFFSET,
                    (float) Math.sin(angle) * RAY_ROOT_RADIUS);
            // Both authored eye faces are textured. Turning around local X mirrors
            // the feather sweep while keeping an eye facing the ground.
            Quaternionf rotation = new Quaternionf().rotateY(-angle)
                    .rotateX((wingFlips[wing] ? -1 : 1) * (float) Math.PI / 2)
                    // ItemDisplay's renderer applies its own local Y half-turn.
                    .rotateY((float) Math.PI);
            float scale = WING_SCALE * visible * smooth((open - 0.2F) / 0.8F);
            return transform(root, rotation, scale, scale, scale);
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
        float radius = RAY_ROOT_RADIUS + rayLength(ray) * fraction * visible;
        return new Vector3f(dx * radius, 0, dz * radius);
    }

    float rayLength(int ray) { return AUTHORED_RAY_HEIGHTS[rayVariants[ray]] / 32.0F * RAY_SCALE * 2; }
    static float rayGrowth(float open) { return smooth((open - 0.12F) / 0.88F); }
    static float smooth(float value) { float t = clamp(value); return t * t * (3 - 2 * t); }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
}
