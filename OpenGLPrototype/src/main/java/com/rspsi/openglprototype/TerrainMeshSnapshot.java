package com.rspsi.openglprototype;

/** Immutable CPU-side geometry packet ready for upload to an OpenGL VBO. */
public final class TerrainMeshSnapshot {
    private final float[] positions;
    private final int[] indices;

    public TerrainMeshSnapshot(float[] positions, int[] indices) {
        this.positions = positions;
        this.indices = indices;
    }

    public float[] getPositions() {
        return positions;
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
