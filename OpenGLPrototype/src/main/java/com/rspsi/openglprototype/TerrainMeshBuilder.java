package com.rspsi.openglprototype;

import com.jagex.cache.def.Floor;
import com.jagex.cache.loader.floor.FloorDefinitionLoader;
import com.jagex.chunk.Chunk;

/**
 * Converts the working editor's existing terrain into a GPU-ready mesh.
 *
 * This pass preserves the live height map and applies the cache floor RGB for
 * each tile. Overlay shapes and textures are intentionally deferred to the
 * next renderer milestones; when an overlay exists its base RGB currently
 * colours the whole tile.
 */
public final class TerrainMeshBuilder {
    private static final int CHUNK_SIZE = 64;
    private static final float TILE_SIZE = 128.0f;

    private TerrainMeshBuilder() {
    }

    public static TerrainMeshSnapshot build(Chunk chunk, int plane) {
        if (chunk == null || chunk.mapRegion == null) {
            return new TerrainMeshSnapshot(new float[0], new float[0], new int[0]);
        }

        // Four vertices per tile deliberately avoids colour bleeding between
        // neighbouring floor definitions and prepares us for shaped overlays.
        final int tileCount = CHUNK_SIZE * CHUNK_SIZE;
        float[] positions = new float[tileCount * 4 * 3];
        float[] colours = new float[tileCount * 4 * 3];
        int[] indices = new int[tileCount * 6];

        int vertexFloat = 0;
        int index = 0;
        int baseVertex = 0;

        for (int y = 0; y < CHUNK_SIZE; y++) {
            for (int x = 0; x < CHUNK_SIZE; x++) {
                int mapX = chunk.offsetX + x;
                int mapY = chunk.offsetY + y;

                float x0 = mapX * TILE_SIZE;
                float x1 = (mapX + 1) * TILE_SIZE;
                float z0 = mapY * TILE_SIZE;
                float z1 = (mapY + 1) * TILE_SIZE;

                float h00 = -chunk.mapRegion.tileHeights[plane][mapX][mapY];
                float h10 = -chunk.mapRegion.tileHeights[plane][mapX + 1][mapY];
                float h01 = -chunk.mapRegion.tileHeights[plane][mapX][mapY + 1];
                float h11 = -chunk.mapRegion.tileHeights[plane][mapX + 1][mapY + 1];

                vertexFloat = putVertex(positions, vertexFloat, x0, h00, z0);
                vertexFloat = putVertex(positions, vertexFloat, x1, h10, z0);
                vertexFloat = putVertex(positions, vertexFloat, x0, h01, z1);
                vertexFloat = putVertex(positions, vertexFloat, x1, h11, z1);

                int rgb = resolveTileRgb(chunk, plane, mapX, mapY);
                float r = ((rgb >> 16) & 0xff) / 255.0f;
                float g = ((rgb >> 8) & 0xff) / 255.0f;
                float b = (rgb & 0xff) / 255.0f;

                // A tiny height-derived modulation gives the untextured preview
                // some depth without attempting to reproduce RS lighting yet.
                float averageHeight = (h00 + h10 + h01 + h11) * 0.25f;
                float shade = clamp(0.93f + averageHeight / 20000.0f, 0.78f, 1.08f);
                for (int vertex = 0; vertex < 4; vertex++) {
                    int colour = (baseVertex + vertex) * 3;
                    colours[colour] = clamp(r * shade, 0.0f, 1.0f);
                    colours[colour + 1] = clamp(g * shade, 0.0f, 1.0f);
                    colours[colour + 2] = clamp(b * shade, 0.0f, 1.0f);
                }

                indices[index++] = baseVertex;
                indices[index++] = baseVertex + 2;
                indices[index++] = baseVertex + 1;
                indices[index++] = baseVertex + 1;
                indices[index++] = baseVertex + 2;
                indices[index++] = baseVertex + 3;
                baseVertex += 4;
            }
        }

        return new TerrainMeshSnapshot(positions, colours, indices);
    }

    private static int putVertex(float[] positions, int p, float x, float y, float z) {
        positions[p++] = x;
        positions[p++] = y;
        positions[p++] = z;
        return p;
    }

    private static int resolveTileRgb(Chunk chunk, int plane, int mapX, int mapY) {
        int overlayValue = chunk.mapRegion.overlays[plane][mapX][mapY] & 0xff;
        if (overlayValue > 0 && FloorDefinitionLoader.instance != null) {
            Floor overlay = FloorDefinitionLoader.getOverlay(overlayValue - 1);
            if (overlay != null) {
                return sanitiseRgb(overlay.getRgb());
            }
        }

        int underlayValue = chunk.mapRegion.underlays[plane][mapX][mapY] & 0xff;
        if (underlayValue > 0 && FloorDefinitionLoader.instance != null) {
            Floor underlay = FloorDefinitionLoader.getUnderlay(underlayValue - 1);
            if (underlay != null) {
                return sanitiseRgb(underlay.getRgb());
            }
        }

        return 0x6f8f43;
    }

    private static int sanitiseRgb(int rgb) {
        // A handful of cache definitions use sentinel-like values; keep the
        // preview readable until the proper texture/secondary-colour path lands.
        if (rgb < 0 || rgb == 0xff00ff) {
            return 0x6f8f43;
        }
        return rgb & 0xffffff;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
