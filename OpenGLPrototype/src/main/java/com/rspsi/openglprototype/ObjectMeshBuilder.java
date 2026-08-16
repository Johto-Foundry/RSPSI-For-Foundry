package com.rspsi.openglprototype;

import com.jagex.Client;
import com.jagex.draw.raster.GameRasterizer;
import com.jagex.entity.Renderable;
import com.jagex.entity.model.Mesh;
import com.jagex.map.SceneGraph;
import com.jagex.map.object.DefaultWorldObject;
import com.jagex.map.object.GameObject;
import com.jagex.map.object.WallDecoration;
import com.jagex.map.tile.SceneTile;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * First GPU object pass. Converts the already-resolved Renderable/Mesh instances
 * stored by RSPSi's SceneGraph into world-space triangles. This deliberately
 * starts with the editor's baked face shading/colours; model texture UVs and
 * alpha/priority ordering are a later pass.
 */
public final class ObjectMeshBuilder {
    private ObjectMeshBuilder() {}

    public static TerrainMeshSnapshot build(int plane) {
        Client client = Client.getSingleton();
        SceneGraph graph = client == null ? null : client.sceneGraph;
        if (graph == null || graph.tiles == null || plane < 0 || plane >= graph.tiles.length) {
            return empty();
        }

        FloatCollector positions = new FloatCollector(262144);
        FloatCollector colours = new FloatCollector(262144);
        FloatCollector uvs = new FloatCollector(131072);
        FloatCollector textureIds = new FloatCollector(65536);
        IntCollector indices = new IntCollector(131072);

        Set<DefaultWorldObject> seen = Collections.newSetFromMap(new IdentityHashMap<DefaultWorldObject, Boolean>());
        int objectCount = 0;
        int meshCount = 0;
        int faceCount = 0;

        for (int x = 0; x < graph.width; x++) {
            for (int y = 0; y < graph.length; y++) {
                SceneTile tile = graph.tiles[plane][x][y];
                if (tile == null) continue;

                if (tile.temporaryObject.isPresent()) {
                    DefaultWorldObject temporary = tile.temporaryObject.get();
                    if (temporary != null && seen.add(temporary)) {
                        objectCount++;
                        faceCount += appendObject(temporary, positions, colours, uvs, textureIds, indices);
                        meshCount += countMeshes(temporary);
                    }
                }

                for (DefaultWorldObject object : tile.getExistingObjects()) {
                    if (object == null || !seen.add(object)) continue;
                    objectCount++;
                    faceCount += appendObject(object, positions, colours, uvs, textureIds, indices);
                    meshCount += countMeshes(object);
                }
            }
        }

        System.out.println("[OPENGL-OBJECTS] objects=" + objectCount
                + " meshes=" + meshCount
                + " faces=" + faceCount
                + " triangles=" + (indices.size / 3));

        return new TerrainMeshSnapshot(
                positions.toArray(), colours.toArray(), uvs.toArray(),
                textureIds.toArray(), indices.toArray());
    }

    private static int countMeshes(DefaultWorldObject object) {
        int count = 0;
        if (resolveMesh(object.getPrimary()) != null) count++;
        if (resolveMesh(object.getSecondary()) != null) count++;
        return count;
    }

    private static int appendObject(DefaultWorldObject object,
                                    FloatCollector positions,
                                    FloatCollector colours,
                                    FloatCollector uvs,
                                    FloatCollector textureIds,
                                    IntCollector indices) {
        int orientation = orientationFor(object);
        int worldX = object instanceof GameObject ? ((GameObject) object).centreX : object.getX();
        int worldZ = object instanceof GameObject ? ((GameObject) object).centreY : object.getY();
        int renderHeight = object.getRenderHeight();

        int faces = 0;
        faces += appendRenderable(object.getPrimary(), worldX, worldZ, renderHeight, orientation,
                positions, colours, uvs, textureIds, indices);
        faces += appendRenderable(object.getSecondary(), worldX, worldZ, renderHeight, orientation,
                positions, colours, uvs, textureIds, indices);
        return faces;
    }

    private static int orientationFor(DefaultWorldObject object) {
        if (object instanceof GameObject) return ((GameObject) object).yaw & 0x7ff;
        if (object instanceof WallDecoration) return ((WallDecoration) object).getOrientation() & 0x7ff;
        // Walls/ground decorations are already constructed in their required
        // orientation by RSPSi's object loader, matching the software renderer's
        // orientation=0 calls for those object types.
        return 0;
    }

    private static Mesh resolveMesh(Renderable renderable) {
        if (renderable == null) return null;
        if (renderable instanceof Mesh) return (Mesh) renderable;
        try {
            return renderable.model();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static int appendRenderable(Renderable renderable,
                                        int worldX, int worldZ, int renderHeight, int orientation,
                                        FloatCollector positions, FloatCollector colours,
                                        FloatCollector uvs, FloatCollector textureIds,
                                        IntCollector indices) {
        Mesh mesh = resolveMesh(renderable);
        if (mesh == null || mesh.verticesX == null || mesh.verticesY == null || mesh.verticesZ == null
                || mesh.faceIndicesA == null || mesh.faceIndicesB == null || mesh.faceIndicesC == null) {
            return 0;
        }

        int vertexCount = Math.min(mesh.numVertices,
                Math.min(mesh.verticesX.length, Math.min(mesh.verticesY.length, mesh.verticesZ.length)));
        int faceCount = Math.min(mesh.numFaces,
                Math.min(mesh.faceIndicesA.length, Math.min(mesh.faceIndicesB.length, mesh.faceIndicesC.length)));
        if (vertexCount <= 0 || faceCount <= 0) return 0;

        double radians = orientation * (Math.PI * 2.0 / 2048.0);
        float sin = (float) Math.sin(radians);
        float cos = (float) Math.cos(radians);
        int emitted = 0;

        for (int face = 0; face < faceCount; face++) {
            if (mesh.faceTypes != null && face < mesh.faceTypes.length && mesh.faceTypes[face] == -1) continue;

            int a = mesh.faceIndicesA[face];
            int b = mesh.faceIndicesB[face];
            int c = mesh.faceIndicesC[face];
            if (!valid(a, vertexCount) || !valid(b, vertexCount) || !valid(c, vertexCount)) continue;

            int colourA = faceColour(mesh, face, 0);
            int colourB = faceColour(mesh, face, 1);
            int colourC = faceColour(mesh, face, 2);
            int base = positions.size / 3;

            appendVertex(mesh, a, worldX, worldZ, renderHeight, sin, cos, colourA,
                    positions, colours, uvs, textureIds);
            appendVertex(mesh, b, worldX, worldZ, renderHeight, sin, cos, colourB,
                    positions, colours, uvs, textureIds);
            appendVertex(mesh, c, worldX, worldZ, renderHeight, sin, cos, colourC,
                    positions, colours, uvs, textureIds);

            indices.add(base);
            indices.add(base + 1);
            indices.add(base + 2);
            emitted++;
        }
        return emitted;
    }

    private static void appendVertex(Mesh mesh, int vertex,
                                     int worldX, int worldZ, int renderHeight,
                                     float sin, float cos, int rgb,
                                     FloatCollector positions, FloatCollector colours,
                                     FloatCollector uvs, FloatCollector textureIds) {
        float localX = mesh.verticesX[vertex];
        float localZ = mesh.verticesZ[vertex];
        // Same fixed-point rotation convention used by Mesh.calculateExtreme.
        float rotatedX = localX * cos + localZ * sin;
        float rotatedZ = localZ * cos - localX * sin;

        positions.add(worldX + rotatedX);
        // Legacy model Y grows downward and renderHeight shares the same legacy
        // vertical axis. OpenGL terrain uses the negated axis, hence both negate.
        positions.add(-renderHeight - mesh.verticesY[vertex]);
        positions.add(worldZ + rotatedZ);

        colours.add(((rgb >> 16) & 255) / 255.0f);
        colours.add(((rgb >> 8) & 255) / 255.0f);
        colours.add((rgb & 255) / 255.0f);
        uvs.add(0.0f);
        uvs.add(0.0f);
        textureIds.add(-1.0f);
    }

    private static int faceColour(Mesh mesh, int face, int corner) {
        int hsl = -1;
        if (corner == 0 && mesh.shadedFaceColoursX != null && face < mesh.shadedFaceColoursX.length) hsl = mesh.shadedFaceColoursX[face];
        if (corner == 1 && mesh.shadedFaceColoursY != null && face < mesh.shadedFaceColoursY.length) hsl = mesh.shadedFaceColoursY[face];
        if (corner == 2 && mesh.shadedFaceColoursZ != null && face < mesh.shadedFaceColoursZ.length) hsl = mesh.shadedFaceColoursZ[face];
        if (hsl < 0 && mesh.faceColours != null && face < mesh.faceColours.length) hsl = mesh.faceColours[face];

        GameRasterizer rasterizer = GameRasterizer.getInstance();
        if (hsl >= 0 && rasterizer != null && rasterizer.colourPalette != null && hsl < rasterizer.colourPalette.length) {
            int rgb = rasterizer.colourPalette[hsl] & 0xffffff;
            if (rgb != 0) return rgb;
        }
        return 0x9b927b;
    }

    private static boolean valid(int index, int length) {
        return index >= 0 && index < length;
    }

    private static TerrainMeshSnapshot empty() {
        return new TerrainMeshSnapshot(new float[0], new float[0], new float[0], new float[0], new int[0]);
    }

    private static final class FloatCollector {
        private float[] data;
        private int size;
        FloatCollector(int capacity) { data = new float[Math.max(32, capacity)]; }
        void add(float value) { if (size == data.length) data = Arrays.copyOf(data, data.length * 2); data[size++] = value; }
        float[] toArray() { return Arrays.copyOf(data, size); }
    }

    private static final class IntCollector {
        private int[] data;
        private int size;
        IntCollector(int capacity) { data = new int[Math.max(32, capacity)]; }
        void add(int value) { if (size == data.length) data = Arrays.copyOf(data, data.length * 2); data[size++] = value; }
        int[] toArray() { return Arrays.copyOf(data, size); }
    }
}
