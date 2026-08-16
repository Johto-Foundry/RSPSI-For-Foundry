package com.rspsi.openglprototype;

/** Immutable CPU-side geometry packet ready for upload to OpenGL buffers. */
public final class TerrainMeshSnapshot {
    private final float[] positions;
    private final float[] colours;
    private final float[] texCoords;
    private final float[] textureIds;
    private final int[] indices;

    public TerrainMeshSnapshot(float[] positions, int[] indices) {
        this(positions, defaultColours(positions.length / 3), indices);
    }

    public TerrainMeshSnapshot(float[] positions, float[] colours, int[] indices) {
        this(positions, colours, defaultTexCoords(positions.length / 3), defaultTextureIds(positions.length / 3), indices);
    }

    public TerrainMeshSnapshot(float[] positions, float[] colours, float[] texCoords, float[] textureIds, int[] indices) {
        int vertices = positions.length / 3;
        if (colours.length != positions.length) {
            throw new IllegalArgumentException("Terrain colours must contain one RGB triplet per vertex");
        }
        if (texCoords.length != vertices * 2) {
            throw new IllegalArgumentException("Terrain UVs must contain one UV pair per vertex");
        }
        if (textureIds.length != vertices) {
            throw new IllegalArgumentException("Terrain texture IDs must contain one value per vertex");
        }
        this.positions = positions;
        this.colours = colours;
        this.texCoords = texCoords;
        this.textureIds = textureIds;
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

    private static float[] defaultTexCoords(int vertexCount) {
        return new float[vertexCount * 2];
    }

    private static float[] defaultTextureIds(int vertexCount) {
        float[] ids = new float[vertexCount];
        java.util.Arrays.fill(ids, -1.0f);
        return ids;
    }

    public float[] getPositions() { return positions; }
    public float[] getColours() { return colours; }
    public float[] getTexCoords() { return texCoords; }
    public float[] getTextureIds() { return textureIds; }
    public int[] getIndices() { return indices; }
    public int getVertexCount() { return positions.length / 3; }
    public int getTriangleCount() { return indices.length / 3; }
}
