package com.rspsi.openglprototype;

import com.jagex.chunk.Chunk;

/**
 * Converts the working editor's existing height map into a simple GPU-ready
 * triangle grid. This is intentionally render-only and never mutates map data.
 */
public final class TerrainMeshBuilder {
    private static final int CHUNK_SIZE = 64;
    private static final float TILE_SIZE = 128.0f;

    private TerrainMeshBuilder() {
    }

    public static TerrainMeshSnapshot build(Chunk chunk, int plane) {
        if (chunk == null || chunk.mapRegion == null) {
            return new TerrainMeshSnapshot(new float[0], new int[0]);
        }

        final int vertsPerSide = CHUNK_SIZE + 1;
        float[] positions = new float[vertsPerSide * vertsPerSide * 3];
        int p = 0;
        for (int y = 0; y <= CHUNK_SIZE; y++) {
            for (int x = 0; x <= CHUNK_SIZE; x++) {
                int mapX = chunk.offsetX + x;
                int mapY = chunk.offsetY + y;
                int height = chunk.mapRegion.tileHeights[plane][mapX][mapY];

                positions[p++] = mapX * TILE_SIZE;
                positions[p++] = -height;
                positions[p++] = mapY * TILE_SIZE;
            }
        }

        int[] indices = new int[CHUNK_SIZE * CHUNK_SIZE * 6];
        int i = 0;
        for (int y = 0; y < CHUNK_SIZE; y++) {
            for (int x = 0; x < CHUNK_SIZE; x++) {
                int a = y * vertsPerSide + x;
                int b = a + 1;
                int c = a + vertsPerSide;
                int d = c + 1;

                indices[i++] = a;
                indices[i++] = c;
                indices[i++] = b;
                indices[i++] = b;
                indices[i++] = c;
                indices[i++] = d;
            }
        }

        return new TerrainMeshSnapshot(positions, indices);
    }
}
