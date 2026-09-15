package com.lostglade.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Orthographic asset preview only; does not emulate the Minecraft renderer. */
final class OrthodoxEyePreview {
    private record Face(Vector3f[] corners, BufferedImage texture, float[] uv, float depth) {}
    private static final Map<String, BufferedImage> TEXTURES = new HashMap<>();

    static void render() throws Exception {
        OrthodoxEyeComposition c = new OrthodoxEyeComposition(42);
        List<Face> faces = new ArrayList<>();
        for (int part = 0; part < OrthodoxEyeComposition.PART_COUNT; part++) {
            collect(faces, OrthodoxEyeCompositionTest.model(c.model(part)), c.transformation(part, 1, 1).getMatrix());
        }
        draw(faces, Path.of("build/reports/divine-eye/composition.png"), 18);
    }

    private static void collect(List<Face> output, JsonObject model, Matrix4fc transform) throws Exception {
        JsonObject textures = model.getAsJsonObject("textures");
        for (var element : model.getAsJsonArray("elements")) {
            JsonObject e = element.getAsJsonObject();
            float[] f = floats(e.getAsJsonArray("from"));
            float[] t = floats(e.getAsJsonArray("to"));
            Vector3f[] vertices = {
                    new Vector3f(f[0],f[1],f[2]), new Vector3f(t[0],f[1],f[2]),
                    new Vector3f(t[0],t[1],f[2]), new Vector3f(f[0],t[1],f[2]),
                    new Vector3f(f[0],f[1],t[2]), new Vector3f(t[0],f[1],t[2]),
                    new Vector3f(t[0],t[1],t[2]), new Vector3f(f[0],t[1],t[2])
            };
            for (Vector3f v : vertices) {
                if (e.has("rotation")) {
                    JsonObject r = e.getAsJsonObject("rotation");
                    Vector3f origin = new Vector3f(floats(r.getAsJsonArray("origin")));
                    float angle = (float) Math.toRadians(r.get("angle").getAsFloat());
                    Quaternionf rotation = new Quaternionf();
                    switch (r.get("axis").getAsString()) {
                        case "x" -> rotation.rotateX(angle);
                        case "y" -> rotation.rotateY(angle);
                        case "z" -> rotation.rotateZ(angle);
                    }
                    v.sub(origin).rotate(rotation).add(origin);
                }
                transform.transformPosition(v.sub(8,8,8).div(16));
            }
            for (var entry : e.getAsJsonObject("faces").entrySet()) {
                int[] indices = switch (entry.getKey()) {
                    case "north" -> new int[]{2,1,0,3};
                    case "south" -> new int[]{7,4,5,6};
                    case "east" -> new int[]{6,5,1,2};
                    case "west" -> new int[]{3,0,4,7};
                    case "up" -> new int[]{3,7,6,2};
                    default -> new int[]{4,0,1,5};
                };
                JsonObject face = entry.getValue().getAsJsonObject();
                String textureName = textures.get(face.get("texture").getAsString().substring(1)).getAsString();
                BufferedImage image = TEXTURES.get(textureName);
                if (image == null) {
                    image = ImageIO.read(Path.of("src/main/resources/assets/lg2/textures/" + textureName.substring(4) + ".png").toFile());
                    TEXTURES.put(textureName, image);
                }
                int rotation = face.has("rotation") ? face.get("rotation").getAsInt() / 90 : 0;
                Vector3f[] corners = new Vector3f[4];
                float depth = 0;
                for (int i = 0; i < 4; i++) {
                    corners[i] = vertices[indices[(i + rotation) % 4]];
                    depth += corners[i].y * 0.94F - corners[i].z * 0.342F;
                }
                output.add(new Face(corners, image, floats(face.getAsJsonArray("uv")), depth / 4));
            }
        }
    }

    private static void draw(List<Face> faces, Path file, float scale) throws Exception {
        BufferedImage image = new BufferedImage(1600,1600,BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(28,34,45)); g.fillRect(0,0,1600,1600);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        faces.sort(Comparator.comparingDouble(Face::depth).reversed());
        for (Face face : faces) {
            double[] x = new double[4], y = new double[4];
            Path2D polygon = new Path2D.Double();
            for (int i=0; i<4; i++) {
                Vector3f v = face.corners[i];
                x[i] = 800 + v.x * scale;
                y[i] = 800 + (v.z * 0.94 + v.y * 0.342) * scale;
                if (i==0) polygon.moveTo(x[i],y[i]); else polygon.lineTo(x[i],y[i]);
            }
            polygon.closePath();
            double u = face.uv[0] / 16 * face.texture.getWidth(), v = face.uv[1] / 16 * face.texture.getHeight();
            double w = (face.uv[2]-face.uv[0]) / 16 * face.texture.getWidth();
            double h = (face.uv[3]-face.uv[1]) / 16 * face.texture.getHeight();
            if (Math.abs(w * h) < 0.00001) continue;
            AffineTransform map = new AffineTransform((x[3]-x[0])/w, (y[3]-y[0])/w,
                    (x[1]-x[0])/h, (y[1]-y[0])/h, x[0], y[0]);
            map.translate(-u,-v);
            g.setClip(polygon); g.drawImage(face.texture, map, null);
        }
        g.dispose();
        Files.createDirectories(file.getParent()); ImageIO.write(image,"png",file.toFile());
    }

    private static float[] floats(JsonArray array) {
        float[] values = new float[array.size()];
        for (int i=0; i<values.length; i++) values[i]=array.get(i).getAsFloat();
        return values;
    }
}
