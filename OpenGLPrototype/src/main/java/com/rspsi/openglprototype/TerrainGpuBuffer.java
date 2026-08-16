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
        gl.glGenVertexArrays(1, handles, 0);
        vao = handles[0];
        gl.glGenBuffers(1, handles, 0);
        vbo = handles[0];
        gl.glGenBuffers(1, handles, 0);
        ebo = handles[0];

        float[] positions = mesh.getPositions();
        float[] colours = mesh.getColours();
        float[] packedVertices = new float[mesh.getVertexCount() * 6];
        for (int vertex = 0; vertex < mesh.getVertexCount(); vertex++) {
            int source = vertex * 3;
            int target = vertex * 6;
            packedVertices[target] = positions[source];
            packedVertices[target + 1] = positions[source + 1];
            packedVertices[target + 2] = positions[source + 2];
            packedVertices[target + 3] = colours[source];
            packedVertices[target + 4] = colours[source + 1];
            packedVertices[target + 5] = colours[source + 2];
        }

        FloatBuffer vertices = Buffers.newDirectFloatBuffer(packedVertices);
        IntBuffer indices = Buffers.newDirectIntBuffer(mesh.getIndices());
        indexCount = mesh.getIndices().length;

        gl.glBindVertexArray(vao);

        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo);
        gl.glBufferData(GL.GL_ARRAY_BUFFER,
                (long) vertices.remaining() * Float.BYTES,
                vertices,
                GL.GL_STATIC_DRAW);

        gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, ebo);
        gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER,
                (long) indices.remaining() * Integer.BYTES,
                indices,
                GL.GL_STATIC_DRAW);

        int stride = 6 * Float.BYTES;
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, stride, 0L);
        gl.glEnableVertexAttribArray(1);
        gl.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, stride, 3L * Float.BYTES);

        gl.glBindVertexArray(0);
    }

    public void draw(GL3 gl) {
        if (vao == 0 || indexCount == 0) {
            return;
        }
        gl.glBindVertexArray(vao);
        gl.glDrawElements(GL.GL_TRIANGLES, indexCount, GL.GL_UNSIGNED_INT, 0L);
        gl.glBindVertexArray(0);
    }

    public void dispose(GL3 gl) {
        if (ebo != 0) {
            gl.glDeleteBuffers(1, new int[] {ebo}, 0);
            ebo = 0;
        }
        if (vbo != 0) {
            gl.glDeleteBuffers(1, new int[] {vbo}, 0);
            vbo = 0;
        }
        if (vao != 0) {
            gl.glDeleteVertexArrays(1, new int[] {vao}, 0);
            vao = 0;
        }
        indexCount = 0;
    }
}
