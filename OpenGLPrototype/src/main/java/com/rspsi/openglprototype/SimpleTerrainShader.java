package com.rspsi.openglprototype;

import com.jogamp.opengl.GL3;
import org.joml.Matrix4f;

/** Minimal shader used to prove the Foundry OpenGL draw pipeline before RuneLite shaders are transplanted. */
public final class SimpleTerrainShader {
    private static final String VERTEX =
            "#version 330 core\n" +
            "layout(location = 0) in vec3 aPosition;\n" +
            "uniform mat4 uViewProjection;\n" +
            "void main() {\n" +
            "    gl_Position = uViewProjection * vec4(aPosition, 1.0);\n" +
            "}\n";

    private static final String FRAGMENT =
            "#version 330 core\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    fragColor = vec4(0.64, 0.78, 0.46, 1.0);\n" +
            "}\n";

    private int program;
    private int viewProjectionLocation = -1;

    public void init(GL3 gl) {
        int vertex = compile(gl, GL3.GL_VERTEX_SHADER, VERTEX);
        int fragment = compile(gl, GL3.GL_FRAGMENT_SHADER, FRAGMENT);
        program = gl.glCreateProgram();
        gl.glAttachShader(program, vertex);
        gl.glAttachShader(program, fragment);
        gl.glLinkProgram(program);

        int[] ok = new int[1];
        gl.glGetProgramiv(program, GL3.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            int[] length = new int[1];
            gl.glGetProgramiv(program, GL3.GL_INFO_LOG_LENGTH, length, 0);
            byte[] log = new byte[Math.max(length[0], 1)];
            gl.glGetProgramInfoLog(program, log.length, null, 0, log, 0);
            throw new IllegalStateException("OpenGL program link failed: " + new String(log));
        }

        viewProjectionLocation = gl.glGetUniformLocation(program, "uViewProjection");
        gl.glDetachShader(program, vertex);
        gl.glDetachShader(program, fragment);
        gl.glDeleteShader(vertex);
        gl.glDeleteShader(fragment);
    }

    private int compile(GL3 gl, int type, String source) {
        int shader = gl.glCreateShader(type);
        String[] sources = {source};
        int[] lengths = {source.length()};
        gl.glShaderSource(shader, 1, sources, lengths, 0);
        gl.glCompileShader(shader);

        int[] ok = new int[1];
        gl.glGetShaderiv(shader, GL3.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            int[] length = new int[1];
            gl.glGetShaderiv(shader, GL3.GL_INFO_LOG_LENGTH, length, 0);
            byte[] log = new byte[Math.max(length[0], 1)];
            gl.glGetShaderInfoLog(shader, log.length, null, 0, log, 0);
            throw new IllegalStateException("OpenGL shader compile failed: " + new String(log));
        }
        return shader;
    }

    public void use(GL3 gl) {
        gl.glUseProgram(program);
    }

    public void setViewProjection(GL3 gl, Matrix4f matrix) {
        if (viewProjectionLocation < 0) {
            return;
        }
        float[] values = new float[16];
        matrix.get(values);
        gl.glUniformMatrix4fv(viewProjectionLocation, 1, false, values, 0);
    }

    public void dispose(GL3 gl) {
        if (program != 0) {
            gl.glDeleteProgram(program);
            program = 0;
            viewProjectionLocation = -1;
        }
    }
}
