package com.rspsi.openglprototype;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Uploads a TerrainMeshSnapshot to VAO/VBO/EBO storage and draws it. */
public final class TerrainGpuBuffer {
    private int vao;
    private int vbo;
    private int ebo;
    private int indexCount;

    public void upload(GL3 gl, TerrainMeshSnapshot mesh) {
        dispose(gl);
        int[] handles = new int[1];
        gl.glGenVertexArrays(1, handles, 0); vao = handles[0];
        gl.glGenBuffers(1, handles, 0); vbo = handles[0];
        gl.glGenBuffers(1, handles, 0); ebo = handles[0];

        float[] positions = mesh.getPositions();
        float[] colours = mesh.getColours();
        float[] uvs = mesh.getTexCoords();
        float[] textureIds = mesh.getTextureIds();
        // xyz + rgb + uv + texture id
        float[] packed = new float[mesh.getVertexCount() * 9];
        for (int v = 0; v < mesh.getVertexCount(); v++) {
            int p3 = v * 3, p2 = v * 2, out = v * 9;
            packed[out] = positions[p3]; packed[out + 1] = positions[p3 + 1]; packed[out + 2] = positions[p3 + 2];
            packed[out + 3] = colours[p3]; packed[out + 4] = colours[p3 + 1]; packed[out + 5] = colours[p3 + 2];
            packed[out + 6] = uvs[p2]; packed[out + 7] = uvs[p2 + 1];
            packed[out + 8] = textureIds[v];
        }

        FloatBuffer vertices = Buffers.newDirectFloatBuffer(packed);
        IntBuffer indices = Buffers.newDirectIntBuffer(mesh.getIndices());
        indexCount = mesh.getIndices().length;

        gl.glBindVertexArray(vao);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) vertices.remaining() * Float.BYTES, vertices, GL.GL_STATIC_DRAW);
        gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, ebo);
        gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, (long) indices.remaining() * Integer.BYTES, indices, GL.GL_STATIC_DRAW);

        int stride = 9 * Float.BYTES;
        gl.glEnableVertexAttribArray(0); gl.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, stride, 0L);
        gl.glEnableVertexAttribArray(1); gl.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, stride, 3L * Float.BYTES);
        gl.glEnableVertexAttribArray(2); gl.glVertexAttribPointer(2, 2, GL.GL_FLOAT, false, stride, 6L * Float.BYTES);
        gl.glEnableVertexAttribArray(3); gl.glVertexAttribPointer(3, 1, GL.GL_FLOAT, false, stride, 8L * Float.BYTES);
        gl.glBindVertexArray(0);
    }

    public void draw(GL3 gl) {
        if (vao == 0 || indexCount == 0) return;
        gl.glBindVertexArray(vao);
        gl.glDrawElements(GL.GL_TRIANGLES, indexCount, GL.GL_UNSIGNED_INT, 0L);
        gl.glBindVertexArray(0);
    }

    public void dispose(GL3 gl) {
        if (ebo != 0) { gl.glDeleteBuffers(1, new int[]{ebo}, 0); ebo = 0; }
        if (vbo != 0) { gl.glDeleteBuffers(1, new int[]{vbo}, 0); vbo = 0; }
        if (vao != 0) { gl.glDeleteVertexArrays(1, new int[]{vao}, 0); vao = 0; }
        indexCount = 0;
    }
}
