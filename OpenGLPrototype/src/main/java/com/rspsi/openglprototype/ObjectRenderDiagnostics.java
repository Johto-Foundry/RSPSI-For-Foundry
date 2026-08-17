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
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only diagnostics for renderer parity. This deliberately does not alter
 * geometry, colours, GL state, culling, alpha, or draw order.
 */
public final class ObjectRenderDiagnostics {
    private static final int MAX_OBJECT_BUCKETS = 24;
    private static final int MAX_SHADE_BUCKETS = 24;
    private static final int MAX_LARGE_FACE_LOGS = 24;
    private static final int MAX_VISIBILITY_SAMPLES = 350000;
    private static final double LARGE_FACE_AREA2 = 18000.0;

    private static final List<FaceSample> visibilitySamples = new ArrayList<>();
    private static long lastVisibilityNanos;

    private ObjectRenderDiagnostics() {}

    public static void run(int plane) {
        Client client = Client.getSingleton();
        SceneGraph graph = client == null ? null : client.sceneGraph;
        if (graph == null || graph.tiles == null || plane < 0 || plane >= graph.tiles.length) {
            System.out.println("[OPENGL-DIAG] Scene unavailable; diagnostics skipped.");
            return;
        }

        Stats stats = new Stats();
        visibilitySamples.clear();
        lastVisibilityNanos = 0L;
        Set<DefaultWorldObject> seen = Collections.newSetFromMap(new IdentityHashMap<DefaultWorldObject, Boolean>());

        for (int x = 0; x < graph.width; x++) {
            for (int y = 0; y < graph.length; y++) {
                SceneTile tile = graph.tiles[plane][x][y];
                if (tile == null) continue;

                if (tile.temporaryObject.isPresent()) {
                    DefaultWorldObject object = tile.temporaryObject.get();
                    if (object != null && seen.add(object)) inspectObject(object, stats);
                }

                for (DefaultWorldObject object : tile.getExistingObjects()) {
                    if (object != null && seen.add(object)) inspectObject(object, stats);
                }
            }
        }

        System.out.println("[OPENGL-COLOR-DIAG] fallbackFaces=" + stats.fallbackFaces
                + " fallbackCorners=" + stats.fallbackCorners
                + " paletteZeroCorners=" + stats.paletteZeroCorners
                + " invalidShadeCorners=" + stats.invalidShadeCorners
                + " rasterizerMissingCorners=" + stats.rasterizerMissingCorners
                + " paletteMissingCorners=" + stats.paletteMissingCorners);
        if (!stats.fallbackByObject.isEmpty()) {
            System.out.println("[OPENGL-COLOR-DIAG] fallbackFacesByObject=" + stats.fallbackByObject);
        }
        if (!stats.zeroPaletteByShade.isEmpty()) {
            System.out.println("[OPENGL-COLOR-DIAG] zeroPaletteCornersByShade=" + stats.zeroPaletteByShade);
        }

        System.out.println("[OPENGL-ALPHA-DIAG] partialAlphaFaces=" + stats.partialAlphaFaces
                + " alphaSkippedFaces=" + stats.alphaSkippedFaces);
        if (!stats.partialAlphaByObject.isEmpty()) {
            System.out.println("[OPENGL-ALPHA-DIAG] partialAlphaFacesByObject=" + stats.partialAlphaByObject);
        }

        System.out.println("[OPENGL-PRIORITY-DIAG] meshesWithPriorities=" + stats.meshesWithPriorities
                + " priorityFaces=" + stats.priorityFaces
                + " priorityBuckets=" + stats.priorityBuckets);
        printPriorityObjects(stats);
        printLargeFaces(stats);

        System.out.println("[OPENGL-VISIBILITY-DIAG] preparedSamples=" + visibilitySamples.size()
                + " (camera-dependent projected winding is evaluated live once per second;"
                + " mirrored OpenGL projection is sign-corrected to match RSPSi's >0 test)." );
    }

    /** Called from the live preview after the current RSPSi-following camera matrix is built. */
    public static void evaluateVisibility(Matrix4f viewProjection) {
        long now = System.nanoTime();
        if (viewProjection == null || visibilitySamples.isEmpty() || now - lastVisibilityNanos < 1_000_000_000L) return;
        lastVisibilityNanos = now;

        int considered = 0;
        int rsVisible = 0;
        int rsRejected = 0;
        int clippedOrBehind = 0;
        Map<Integer, Integer> rejectedByObject = new LinkedHashMap<>();
        Map<Integer, Integer> visibleByObject = new LinkedHashMap<>();

        Vector4f ca = new Vector4f();
        Vector4f cb = new Vector4f();
        Vector4f cc = new Vector4f();
        for (FaceSample f : visibilitySamples) {
            ca.set(f.ax, f.ay, f.az, 1.0f).mul(viewProjection);
            cb.set(f.bx, f.by, f.bz, 1.0f).mul(viewProjection);
            cc.set(f.cx, f.cy, f.cz, 1.0f).mul(viewProjection);
            if (ca.w <= 0.0f || cb.w <= 0.0f || cc.w <= 0.0f) {
                clippedOrBehind++;
                continue;
            }
            float ax = ca.x / ca.w, ay = ca.y / ca.w;
            float bx = cb.x / cb.w, by = cb.y / cb.w;
            float cx = cc.x / cc.w, cy = cc.y / cc.w;
            float glArea = (ax - bx) * (cy - by) - (ay - by) * (cx - bx);
            // LiveTerrainPreview mirrors X to correct RSPSi's camera handedness, so undo that sign here.
            float rsArea = -glArea;
            considered++;
            if (rsArea > 0.0f) {
                rsVisible++;
                cappedMerge(visibleByObject, f.objectId, 1, 12);
            } else {
                rsRejected++;
                cappedMerge(rejectedByObject, f.objectId, 1, 12);
            }
        }

        System.out.println("[OPENGL-VISIBILITY-DIAG] considered=" + considered
                + " rsVisible=" + rsVisible
                + " rsRejected=" + rsRejected
                + " clippedOrBehind=" + clippedOrBehind);
        if (!rejectedByObject.isEmpty()) {
            System.out.println("[OPENGL-VISIBILITY-DIAG] rejectedFacesByObject(firstSeen)=" + rejectedByObject);
        }
    }

    private static void inspectObject(DefaultWorldObject object, Stats stats) {
        stats.objects++;
        int orientation = orientationFor(object);
        int worldX = object instanceof GameObject ? ((GameObject) object).centreX : object.getX();
        int worldZ = object instanceof GameObject ? ((GameObject) object).centreY : object.getY();
        int renderHeight = object.getRenderHeight();
        inspectRenderable(object.getPrimary(), object.getId(), worldX, worldZ, renderHeight, orientation, stats);
        inspectRenderable(object.getSecondary(), object.getId(), worldX, worldZ, renderHeight, orientation, stats);
    }

    private static int orientationFor(DefaultWorldObject object) {
        if (object instanceof GameObject) return ((GameObject) object).yaw & 0x7ff;
        if (object instanceof WallDecoration) return ((WallDecoration) object).getOrientation() & 0x7ff;
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

    private static void inspectRenderable(Renderable renderable, int objectId, int worldX, int worldZ,
                                          int renderHeight, int orientation, Stats stats) {
        Mesh mesh = resolveMesh(renderable);
        if (mesh == null || mesh.faceIndicesA == null || mesh.faceIndicesB == null || mesh.faceIndicesC == null
                || mesh.verticesX == null || mesh.verticesY == null || mesh.verticesZ == null) return;

        stats.meshes++;
        int vertexCount = Math.min(mesh.numVertices,
                Math.min(mesh.verticesX.length, Math.min(mesh.verticesY.length, mesh.verticesZ.length)));
        int faceCount = Math.min(mesh.numFaces,
                Math.min(mesh.faceIndicesA.length, Math.min(mesh.faceIndicesB.length, mesh.faceIndicesC.length)));
        if (faceCount <= 0 || vertexCount <= 0) return;

        if (mesh.facePriorities != null) stats.meshesWithPriorities++;
        PriorityObjectStats objectStats = stats.priorityByObject.computeIfAbsent(objectId, k -> new PriorityObjectStats(objectId));

        double radians = orientation * (Math.PI * 2.0 / 2048.0);
        float sin = (float) Math.sin(radians), cos = (float) Math.cos(radians);

        for (int face = 0; face < faceCount; face++) {
            if (mesh.faceTypes != null && face < mesh.faceTypes.length && mesh.faceTypes[face] == -1) continue;
            int a = mesh.faceIndicesA[face], b = mesh.faceIndicesB[face], c = mesh.faceIndicesC[face];
            if (!valid(a, vertexCount) || !valid(b, vertexCount) || !valid(c, vertexCount)) continue;

            int alpha = mesh.faceAlphas != null && face < mesh.faceAlphas.length ? mesh.faceAlphas[face] & 0xff : 0;
            if (alpha >= 250) {
                stats.alphaSkippedFaces++;
            } else if (alpha > 0) {
                stats.partialAlphaFaces++;
                cappedMerge(stats.partialAlphaByObject, objectId, 1, MAX_OBJECT_BUCKETS);
            }

            int priority = -1;
            if (mesh.facePriorities != null && face < mesh.facePriorities.length) {
                priority = mesh.facePriorities[face] & 0xff;
                stats.priorityFaces++;
                stats.priorityBuckets.merge(priority, 1, Integer::sum);
                objectStats.priorityFaces++;
                objectStats.buckets.merge(priority, 1, Integer::sum);
                if (priority >= 10) objectStats.highPriorityFaces++;
            }

            int textureId = mesh.faceTextures != null && face < mesh.faceTextures.length ? mesh.faceTextures[face] : -1;
            int type = mesh.faceTypes == null ? (textureId >= 0 ? 2 : 0) : (mesh.faceTypes[face] & 3);
            boolean textured = (type == 2 || type == 3) && textureId >= 0;
            boolean flat = type == 1 || type == 3;

            WorldVertex wa = worldVertex(mesh, a, worldX, worldZ, renderHeight, sin, cos);
            WorldVertex wb = worldVertex(mesh, b, worldX, worldZ, renderHeight, sin, cos);
            WorldVertex wc = worldVertex(mesh, c, worldX, worldZ, renderHeight, sin, cos);
            double area2 = triangleArea2(wa, wb, wc);
            if (area2 > objectStats.maxArea2) objectStats.maxArea2 = area2;
            if (area2 >= LARGE_FACE_AREA2) {
                objectStats.largeFaces++;
                stats.largeFaces.add(new LargeFace(objectId, face, priority, type, alpha, textureId, area2));
            }

            // Keep all suspicious faces plus a broad baseline sample. This remains diagnostics-only.
            if (visibilitySamples.size() < MAX_VISIBILITY_SAMPLES
                    && (priority >= 10 || alpha > 0 || area2 >= LARGE_FACE_AREA2 || (face & 3) == 0)) {
                visibilitySamples.add(new FaceSample(objectId, wa, wb, wc));
            }

            if (textured) continue; // palette fallback under test only exists on the non-textured path.

            boolean faceFallback = false;
            for (int corner = 0; corner < 3; corner++) {
                PaletteLookup lookup = inspectPaletteLookup(mesh, face, corner, flat);
                if (!lookup.fallback) continue;
                faceFallback = true;
                stats.fallbackCorners++;
                switch (lookup.reason) {
                    case PALETTE_ZERO:
                        stats.paletteZeroCorners++;
                        cappedMerge(stats.zeroPaletteByShade, lookup.shade, 1, MAX_SHADE_BUCKETS);
                        break;
                    case INVALID_SHADE:
                        stats.invalidShadeCorners++;
                        break;
                    case RASTERIZER_MISSING:
                        stats.rasterizerMissingCorners++;
                        break;
                    case PALETTE_MISSING:
                        stats.paletteMissingCorners++;
                        break;
                    default:
                        break;
                }
            }

            if (faceFallback) {
                stats.fallbackFaces++;
                cappedMerge(stats.fallbackByObject, objectId, 1, MAX_OBJECT_BUCKETS);
            }
        }
    }

    private static WorldVertex worldVertex(Mesh mesh, int v, int worldX, int worldZ, int renderHeight, float sin, float cos) {
        float lx = mesh.verticesX[v], ly = mesh.verticesY[v], lz = mesh.verticesZ[v];
        float rx = lx * cos + lz * sin;
        float rz = lz * cos - lx * sin;
        return new WorldVertex(worldX + rx, -renderHeight - ly, worldZ + rz);
    }

    private static double triangleArea2(WorldVertex a, WorldVertex b, WorldVertex c) {
        double abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
        double acx = c.x - a.x, acy = c.y - a.y, acz = c.z - a.z;
        double cx = aby * acz - abz * acy;
        double cy = abz * acx - abx * acz;
        double cz = abx * acy - aby * acx;
        return Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    private static void printPriorityObjects(Stats stats) {
        List<PriorityObjectStats> list = new ArrayList<>(stats.priorityByObject.values());
        list.removeIf(s -> s.priorityFaces == 0 && s.largeFaces == 0);
        list.sort((a, b) -> {
            int high = Integer.compare(b.highPriorityFaces, a.highPriorityFaces);
            if (high != 0) return high;
            int large = Integer.compare(b.largeFaces, a.largeFaces);
            if (large != 0) return large;
            return Integer.compare(b.priorityFaces, a.priorityFaces);
        });
        int limit = Math.min(20, list.size());
        for (int i = 0; i < limit; i++) {
            PriorityObjectStats s = list.get(i);
            System.out.println("[OPENGL-OBJECT-PRIORITY] object=" + s.objectId
                    + " priorityFaces=" + s.priorityFaces
                    + " highPriority10_11=" + s.highPriorityFaces
                    + " largeFaces=" + s.largeFaces
                    + " maxArea2=" + Math.round(s.maxArea2)
                    + " buckets=" + s.buckets);
        }
    }

    private static void printLargeFaces(Stats stats) {
        stats.largeFaces.sort(Comparator.comparingDouble((LargeFace f) -> f.area2).reversed());
        int limit = Math.min(MAX_LARGE_FACE_LOGS, stats.largeFaces.size());
        for (int i = 0; i < limit; i++) {
            LargeFace f = stats.largeFaces.get(i);
            System.out.println("[OPENGL-LARGE-FACE] object=" + f.objectId
                    + " face=" + f.face
                    + " priority=" + f.priority
                    + " type=" + f.type
                    + " alpha=" + f.alpha
                    + " texture=" + f.textureId
                    + " area2=" + Math.round(f.area2));
        }
    }

    private static PaletteLookup inspectPaletteLookup(Mesh mesh, int face, int corner, boolean flat) {
        int shade = -1;
        if (mesh.shadedFaceColoursX != null && face < mesh.shadedFaceColoursX.length) shade = mesh.shadedFaceColoursX[face];
        if (!flat) {
            if (corner == 1 && mesh.shadedFaceColoursY != null && face < mesh.shadedFaceColoursY.length) shade = mesh.shadedFaceColoursY[face];
            if (corner == 2 && mesh.shadedFaceColoursZ != null && face < mesh.shadedFaceColoursZ.length) shade = mesh.shadedFaceColoursZ[face];
        }
        if (shade < 0 && mesh.faceColours != null && face < mesh.faceColours.length) shade = mesh.faceColours[face];

        GameRasterizer rasterizer = GameRasterizer.getInstance();
        if (rasterizer == null) return PaletteLookup.fallback(shade, FallbackReason.RASTERIZER_MISSING);
        if (rasterizer.colourPalette == null) return PaletteLookup.fallback(shade, FallbackReason.PALETTE_MISSING);
        if (shade < 0 || shade >= rasterizer.colourPalette.length) return PaletteLookup.fallback(shade, FallbackReason.INVALID_SHADE);
        if ((rasterizer.colourPalette[shade] & 0xffffff) == 0) return PaletteLookup.fallback(shade, FallbackReason.PALETTE_ZERO);
        return PaletteLookup.ok(shade);
    }

    private static boolean valid(int i, int n) { return i >= 0 && i < n; }

    private static void cappedMerge(Map<Integer, Integer> map, int key, int amount, int limit) {
        if (map.containsKey(key)) map.put(key, map.get(key) + amount);
        else if (map.size() < limit) map.put(key, amount);
    }

    private enum FallbackReason { NONE, PALETTE_ZERO, INVALID_SHADE, RASTERIZER_MISSING, PALETTE_MISSING }

    private static final class PaletteLookup {
        final int shade;
        final boolean fallback;
        final FallbackReason reason;
        private PaletteLookup(int shade, boolean fallback, FallbackReason reason) {
            this.shade = shade; this.fallback = fallback; this.reason = reason;
        }
        static PaletteLookup ok(int shade) { return new PaletteLookup(shade, false, FallbackReason.NONE); }
        static PaletteLookup fallback(int shade, FallbackReason reason) { return new PaletteLookup(shade, true, reason); }
    }

    private static final class WorldVertex {
        final float x, y, z;
        WorldVertex(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    }

    private static final class FaceSample {
        final int objectId;
        final float ax, ay, az, bx, by, bz, cx, cy, cz;
        FaceSample(int objectId, WorldVertex a, WorldVertex b, WorldVertex c) {
            this.objectId = objectId;
            ax = a.x; ay = a.y; az = a.z;
            bx = b.x; by = b.y; bz = b.z;
            cx = c.x; cy = c.y; cz = c.z;
        }
    }

    private static final class LargeFace {
        final int objectId, face, priority, type, alpha, textureId;
        final double area2;
        LargeFace(int objectId, int face, int priority, int type, int alpha, int textureId, double area2) {
            this.objectId = objectId; this.face = face; this.priority = priority; this.type = type;
            this.alpha = alpha; this.textureId = textureId; this.area2 = area2;
        }
    }

    private static final class PriorityObjectStats {
        final int objectId;
        int priorityFaces, highPriorityFaces, largeFaces;
        double maxArea2;
        final Map<Integer, Integer> buckets = new LinkedHashMap<>();
        PriorityObjectStats(int objectId) { this.objectId = objectId; }
    }

    private static final class Stats {
        int objects, meshes;
        int fallbackFaces, fallbackCorners, paletteZeroCorners, invalidShadeCorners, rasterizerMissingCorners, paletteMissingCorners;
        int partialAlphaFaces, alphaSkippedFaces, meshesWithPriorities, priorityFaces;
        final Map<Integer, Integer> fallbackByObject = new LinkedHashMap<>();
        final Map<Integer, Integer> zeroPaletteByShade = new LinkedHashMap<>();
        final Map<Integer, Integer> partialAlphaByObject = new LinkedHashMap<>();
        final Map<Integer, Integer> priorityBuckets = new LinkedHashMap<>();
        final Map<Integer, PriorityObjectStats> priorityByObject = new LinkedHashMap<>();
        final List<LargeFace> largeFaces = new ArrayList<>();
    }
}
