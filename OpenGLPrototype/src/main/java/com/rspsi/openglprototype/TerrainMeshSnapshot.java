package com.rspsi.openglprototype;

/** Immutable CPU-side geometry packet ready for upload to OpenGL buffers. */
public final class TerrainMeshSnapshot {
    private final float[] positions;
    private final float[] colours;
    private final int[] indices;

    public TerrainMeshSnapshot(float[] positions, int[] indices) {
        this(positions, defaultColours(positions.length / 3), indices);
    }

    public TerrainMeshSnapshot(float[] positions, float[] colours, int[] indices) {
        if (colours.length != positions.length) {
            throw new IllegalArgumentException("Terrain colours must contain one RGB triplet per vertex");
        }
        this.positions = positions;
        this.colours = colours;
        this.indices = indices;
    }

    private static float[] defaultColours(int vertexCount) {
        float[] colours = new float[vertexCount * 3];
        for (int i = 0; i < vertexCount; i++) {
            int p = i * 3;
            colours[p] = 0.64f;
            colours[p + 1] = 0.78f;
            colours[p + 2] = 0.46f;
        }
        return colours;
    }

    public float[] getPositions() {
        return positions;
    }

    public float[] getColours() {
        return colours;
    }

    public int[] getIndices() {
        return indices;
    }

    public int getVertexCount() {
        return positions.length / 3;
    }

    public int getTriangleCount() {
        return indices.length / 3;
    }
}
