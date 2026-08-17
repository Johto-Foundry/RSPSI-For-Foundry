package com.rspsi.openglprototype;

import com.jagex.Client;
import com.jagex.draw.raster.GameRasterizer;
import com.jagex.entity.Renderable;
import com.jagex.entity.model.Mesh;
import com.jagex.map.SceneGraph;
import com.jagex.map.object.DefaultWorldObject;
import com.jagex.map.tile.SceneTile;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Read-only diagnostics for renderer parity. This deliberately does not alter
 * geometry, colours, GL state, or draw order. It only reports RSPSi mesh data
 * that the current flattened OpenGL object pass may be losing/approximating.
 */
public final class ObjectRenderDiagnostics {
    private static final int MAX_OBJECT_BUCKETS = 24;
    private static final int MAX_SHADE_BUCKETS = 24;

    private ObjectRenderDiagnostics() {}

    public static void run(int plane) {
        Client client = Client.getSingleton();
        SceneGraph graph = client == null ? null : client.sceneGraph;
        if (graph == null || graph.tiles == null || plane < 0 || plane >= graph.tiles.length) {
            System.out.println("[OPENGL-DIAG] Scene unavailable; diagnostics skipped.");
            return;
        }

        Stats stats = new Stats();
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

        System.out.println("[OPENGL-VISIBILITY-DIAG] screenSpaceReject=not-evaluated"
                + " (requires the exact per-frame RSPSi projected vertices; no approximation was used)." );
    }

    private static void inspectObject(DefaultWorldObject object, Stats stats) {
        stats.objects++;
        inspectRenderable(object.getPrimary(), object.getId(), stats);
        inspectRenderable(object.getSecondary(), object.getId(), stats);
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

    private static void inspectRenderable(Renderable renderable, int objectId, Stats stats) {
        Mesh mesh = resolveMesh(renderable);
        if (mesh == null || mesh.faceIndicesA == null || mesh.faceIndicesB == null || mesh.faceIndicesC == null) return;

        stats.meshes++;
        int faceCount = Math.min(mesh.numFaces,
                Math.min(mesh.faceIndicesA.length, Math.min(mesh.faceIndicesB.length, mesh.faceIndicesC.length)));
        if (faceCount <= 0) return;

        if (mesh.facePriorities != null) {
            stats.meshesWithPriorities++;
        }

        for (int face = 0; face < faceCount; face++) {
            if (mesh.faceTypes != null && face < mesh.faceTypes.length && mesh.faceTypes[face] == -1) continue;

            int alpha = mesh.faceAlphas != null && face < mesh.faceAlphas.length ? mesh.faceAlphas[face] & 0xff : 0;
            if (alpha >= 250) {
                stats.alphaSkippedFaces++;
            } else if (alpha > 0) {
                stats.partialAlphaFaces++;
                cappedMerge(stats.partialAlphaByObject, objectId, 1, MAX_OBJECT_BUCKETS);
            }

            if (mesh.facePriorities != null && face < mesh.facePriorities.length) {
                int priority = mesh.facePriorities[face];
                stats.priorityFaces++;
                stats.priorityBuckets.merge(priority, 1, Integer::sum);
            }

            int textureId = mesh.faceTextures != null && face < mesh.faceTextures.length ? mesh.faceTextures[face] : -1;
            int type = mesh.faceTypes == null ? (textureId >= 0 ? 2 : 0) : (mesh.faceTypes[face] & 3);
            boolean textured = (type == 2 || type == 3) && textureId >= 0;
            boolean flat = type == 1 || type == 3;
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

    private static PaletteLookup inspectPaletteLookup(Mesh mesh, int face, int corner, boolean flat) {
        int shade = -1;
        if (mesh.shadedFaceColoursX != null && face < mesh.shadedFaceColoursX.length) {
            shade = mesh.shadedFaceColoursX[face];
        }
        if (!flat) {
            if (corner == 1 && mesh.shadedFaceColoursY != null && face < mesh.shadedFaceColoursY.length) {
                shade = mesh.shadedFaceColoursY[face];
            }
            if (corner == 2 && mesh.shadedFaceColoursZ != null && face < mesh.shadedFaceColoursZ.length) {
                shade = mesh.shadedFaceColoursZ[face];
            }
        }
        if (shade < 0 && mesh.faceColours != null && face < mesh.faceColours.length) {
            shade = mesh.faceColours[face];
        }

        GameRasterizer rasterizer = GameRasterizer.getInstance();
        if (rasterizer == null) return PaletteLookup.fallback(shade, FallbackReason.RASTERIZER_MISSING);
        if (rasterizer.colourPalette == null) return PaletteLookup.fallback(shade, FallbackReason.PALETTE_MISSING);
        if (shade < 0 || shade >= rasterizer.colourPalette.length) {
            return PaletteLookup.fallback(shade, FallbackReason.INVALID_SHADE);
        }
        if ((rasterizer.colourPalette[shade] & 0xffffff) == 0) {
            return PaletteLookup.fallback(shade, FallbackReason.PALETTE_ZERO);
        }
        return PaletteLookup.ok(shade);
    }

    private static void cappedMerge(Map<Integer, Integer> map, int key, int amount, int limit) {
        if (map.containsKey(key)) {
            map.put(key, map.get(key) + amount);
        } else if (map.size() < limit) {
            map.put(key, amount);
        }
    }

    private enum FallbackReason {
        NONE,
        PALETTE_ZERO,
        INVALID_SHADE,
        RASTERIZER_MISSING,
        PALETTE_MISSING
    }

    private static final class PaletteLookup {
        final int shade;
        final boolean fallback;
        final FallbackReason reason;

        private PaletteLookup(int shade, boolean fallback, FallbackReason reason) {
            this.shade = shade;
            this.fallback = fallback;
            this.reason = reason;
        }

        static PaletteLookup ok(int shade) {
            return new PaletteLookup(shade, false, FallbackReason.NONE);
        }

        static PaletteLookup fallback(int shade, FallbackReason reason) {
            return new PaletteLookup(shade, true, reason);
        }
    }

    private static final class Stats {
        int objects;
        int meshes;
        int fallbackFaces;
        int fallbackCorners;
        int paletteZeroCorners;
        int invalidShadeCorners;
        int rasterizerMissingCorners;
        int paletteMissingCorners;
        int partialAlphaFaces;
        int alphaSkippedFaces;
        int meshesWithPriorities;
        int priorityFaces;

        final Map<Integer, Integer> fallbackByObject = new LinkedHashMap<>();
        final Map<Integer, Integer> zeroPaletteByShade = new LinkedHashMap<>();
        final Map<Integer, Integer> partialAlphaByObject = new LinkedHashMap<>();
        final Map<Integer, Integer> priorityBuckets = new LinkedHashMap<>();
    }
}
