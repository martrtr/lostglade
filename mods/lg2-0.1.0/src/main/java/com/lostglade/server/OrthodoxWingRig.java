package com.lostglade.server;

import com.google.gson.Gson;
import com.mojang.math.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Bone-local geometry and animation imported from the original Figura avatar. */
final class OrthodoxWingRig {
    static final int BONE_COUNT = 12;
    static final float ROOT_HEIGHT = 1.173F;
    static final float ROOT_BACK = 0.115F;
    static final float SCALE = 1.0F;
    static final float ROOT_SPREAD = 0.11F;
    private static final Data DATA = load();

    record Pose(Vector3f position, Quaternionf rotation) {}

    private OrthodoxWingRig() {}

    static Pose[] sample(float seconds, float flightBlend) {
        float blend = Math.clamp(flightBlend, 0, 1);
        Pose[] result = new Pose[BONE_COUNT];
        for (int i = 0; i < result.length; i++) {
            Bone bone = DATA.bones[i];
            Vector3f angles = new Vector3f(bone.idleRotation).lerp(sampleRotation(bone, seconds), blend);
            Vector3f offset = new Vector3f(bone.pivot);
            if (bone.parent >= 0) offset.sub(new Vector3f(DATA.bones[bone.parent].pivot));
            else offset.sub(0, 17.5F, 2);
            Vector3f position = new Vector3f(bone.idlePosition).mul(1 - blend);
            // Figura animation channels invert X position and X/Y rotation, not the mesh coordinates.
            offset.add(-position.x, position.y, position.z).mul(SCALE / 16);
            Quaternionf rotation = new Quaternionf().rotationZYX(
                    (float) Math.toRadians(angles.z),
                    (float) Math.toRadians(-angles.y),
                    (float) Math.toRadians(-angles.x));
            if (bone.parent >= 0) {
                Pose parent = result[bone.parent];
                parent.rotation.transform(offset);
                offset.add(parent.position);
                rotation.premul(parent.rotation);
            } else {
                offset.x = Math.copySign(ROOT_SPREAD, bone.pivot[0]);
                offset.add(0, ROOT_HEIGHT, ROOT_BACK);
            }
            result[i] = new Pose(offset, rotation);
        }
        return result;
    }

    static Transformation displayTransformation(Pose pose, float seatHeight) {
        Vector3f position = new Vector3f(pose.position()).add(0, -seatHeight, 0);
        // ItemDisplayRenderer applies an extra Y half-turn after the display transformation.
        // Cancel it in model space, without rotating the bone's attachment or its children.
        return new Transformation(position, new Quaternionf(pose.rotation()), new Vector3f(SCALE),
                new Quaternionf(0, 1, 0, 0));
    }

    private static Vector3f sampleRotation(Bone bone, float seconds) {
        Key[] keys = bone.flyRotation;
        int count = keys.length - 1; // The final key duplicates the beginning of the loop.
        float time = ((seconds % DATA.duration) + DATA.duration) % DATA.duration;
        int index = 0;
        while (index + 1 < count && keys[index + 1].time <= time) index++;
        float t = (time - keys[index].time) / (keys[index + 1].time - keys[index].time);
        Vector3f value = new Vector3f();
        for (int axis = 0; axis < 3; axis++) {
            float a = keys[(index + count - 1) % count].value[axis];
            float b = keys[index].value[axis];
            float c = keys[index + 1].value[axis];
            float d = keys[(index + 2) % count].value[axis];
            value.setComponent(axis, 0.5F * (2 * b + (-a + c) * t
                    + (2 * a - 5 * b + 4 * c - d) * t * t
                    + (-a + 3 * b - 3 * c + d) * t * t * t));
        }
        return value;
    }

    private static Data load() {
        try (var reader = new InputStreamReader(Objects.requireNonNull(
                OrthodoxWingRig.class.getResourceAsStream("/lg2/orthodox_wing_rig.json")), StandardCharsets.UTF_8)) {
            Data data = new Gson().fromJson(reader, Data.class);
            if (data.bones.length != BONE_COUNT || data.duration <= 0) throw new IllegalStateException("Invalid wing rig");
            for (int i = 0; i < data.bones.length; i++) {
                Bone bone = data.bones[i];
                if (bone.parent >= i || bone.flyRotation.length < 3) throw new IllegalStateException("Invalid wing hierarchy");
            }
            return data;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load Orthodox wing rig", exception);
        }
    }

    /** Pause the fly clock on the ground, then blend local poses without moving the attachment. */
    static final class Animation {
        private int blendTicks;
        private float seconds;
        private final Pose[] folded = sample(0, 0);

        Pose[] tick(boolean flying) {
            blendTicks = Math.clamp(blendTicks + (flying ? 1 : -1), 0, 6);
            if (flying) seconds = (seconds + 0.05F) % DATA.duration;
            if (blendTicks == 0) {
                seconds = 0;
                return folded;
            }
            return sample(seconds, blendTicks / 6.0F);
        }
    }

    private static final class Data { float duration; Bone[] bones; }
    private static final class Bone {
        int parent;
        float[] pivot;
        float[] idleRotation;
        float[] idlePosition;
        Key[] flyRotation;
    }
    private static final class Key { float time; float[] value; }
}
