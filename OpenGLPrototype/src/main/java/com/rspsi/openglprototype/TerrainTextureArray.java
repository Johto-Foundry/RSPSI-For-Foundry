package com.rspsi.openglprototype;

import com.jagex.cache.loader.textures.TextureLoader;
import com.jagex.draw.textures.Texture;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;

import java.nio.ByteBuffer;

/** Uploads the editor's decoded cache textures into one GPU texture array. */
public final class TerrainTextureArray {
    private static final int LAYER_SIZE = 128;
    private int handle;
    private int layerCount;

    public void upload(GL3 gl) {
        dispose(gl);
        TextureLoader loader = TextureLoader.instance;
        if (loader == null || loader.count() <= 0) {
            System.out.println("[OPENGL-TEXTURES] Texture loader unavailable; colour fallback remains active.");
            return;
        }

        int requested = loader.count();
        int[] maxLayers = new int[1];
        gl.glGetIntegerv(GL3.GL_MAX_ARRAY_TEXTURE_LAYERS, maxLayers, 0);
        layerCount = Math.min(requested, Math.max(1, maxLayers[0]));

        int[] id = new int[1];
        gl.glGenTextures(1, id, 0);
        handle = id[0];
        gl.glActiveTexture(GL.GL_TEXTURE0);
        gl.glBindTexture(GL3.GL_TEXTURE_2D_ARRAY, handle);
        gl.glTexParameteri(GL3.GL_TEXTURE_2D_ARRAY, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL3.GL_TEXTURE_2D_ARRAY, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL3.GL_TEXTURE_2D_ARRAY, GL.GL_TEXTURE_WRAP_S, GL.GL_REPEAT);
        gl.glTexParameteri(GL3.GL_TEXTURE_2D_ARRAY, GL.GL_TEXTURE_WRAP_T, GL.GL_REPEAT);
        gl.glTexImage3D(GL3.GL_TEXTURE_2D_ARRAY, 0, GL.GL_RGBA8, LAYER_SIZE, LAYER_SIZE, layerCount, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null);

        int uploaded = 0;
        for (int textureId = 0; textureId < layerCount; textureId++) {
            Texture texture;
            try { texture = TextureLoader.getTexture(textureId); } catch (RuntimeException ex) { continue; }
            if (texture == null || texture.getPixels() == null || texture.getWidth() <= 0 || texture.getHeight() <= 0) continue;
            ByteBuffer rgba = resample(texture);
            gl.glTexSubImage3D(GL3.GL_TEXTURE_2D_ARRAY, 0, 0, 0, textureId, LAYER_SIZE, LAYER_SIZE, 1, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, rgba);
            uploaded++;
        }
        gl.glBindTexture(GL3.GL_TEXTURE_2D_ARRAY, 0);
        System.out.println("[OPENGL-TEXTURES] GPU texture array ready: " + uploaded + "/" + layerCount + " cache textures (" + LAYER_SIZE + "x" + LAYER_SIZE + ").");
    }

    private ByteBuffer resample(Texture texture) {
        int width = texture.getWidth(), height = texture.getHeight();
        int[] pixels = texture.getPixels();
        boolean alpha = texture.supportsAlpha();
        ByteBuffer out = ByteBuffer.allocateDirect(LAYER_SIZE * LAYER_SIZE * 4);
        for (int y = 0; y < LAYER_SIZE; y++) {
            int sy = Math.min(height - 1, y * height / LAYER_SIZE);
            for (int x = 0; x < LAYER_SIZE; x++) {
                int sx = Math.min(width - 1, x * width / LAYER_SIZE);
                int rgb = pixels[sx + sy * width];
                int a = alpha ? ((rgb >>> 24) & 0xff) : 0xff;
                if ((rgb & 0xffffff) == 0xff00ff) a = 0;
                out.put((byte)((rgb >> 16) & 0xff)); out.put((byte)((rgb >> 8) & 0xff)); out.put((byte)(rgb & 0xff)); out.put((byte)a);
            }
        }
        out.flip();
        return out;
    }

    public void bind(GL3 gl) {
        gl.glActiveTexture(GL.GL_TEXTURE0);
        gl.glBindTexture(GL3.GL_TEXTURE_2D_ARRAY, handle);
    }

    public int getLayerCount() { return layerCount; }

    public void dispose(GL3 gl) {
        if (handle != 0) { gl.glDeleteTextures(1, new int[]{handle}, 0); handle = 0; }
        layerCount = 0;
    }
}
