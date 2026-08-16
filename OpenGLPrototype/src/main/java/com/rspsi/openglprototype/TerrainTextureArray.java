package com.rspsi.openglprototype;

import com.jagex.cache.loader.textures.TextureLoader;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;

import java.nio.ByteBuffer;

/** Uploads the exact 128x128 texture texels used by RSPSi's software rasterizer. */
public final class TerrainTextureArray {
    private static final int LAYER_SIZE = 128;
    private static final int TEXELS_PER_LAYER = LAYER_SIZE * LAYER_SIZE;
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
            int[] pixels;
            try { pixels = TextureLoader.getTexturePixels(textureId); } catch (RuntimeException ex) { continue; }
            if (pixels == null || pixels.length < TEXELS_PER_LAYER) continue;
            ByteBuffer rgba = rasterizerPixelsToRgba(pixels);
            gl.glTexSubImage3D(GL3.GL_TEXTURE_2D_ARRAY, 0, 0, 0, textureId, LAYER_SIZE, LAYER_SIZE, 1, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, rgba);
            uploaded++;
        }
        gl.glBindTexture(GL3.GL_TEXTURE_2D_ARRAY, 0);
        System.out.println("[OPENGL-TEXTURES] GPU texture array ready: " + uploaded + "/" + layerCount + " rasterizer textures (" + LAYER_SIZE + "x" + LAYER_SIZE + ").");
    }

    private ByteBuffer rasterizerPixelsToRgba(int[] pixels) {
        ByteBuffer out = ByteBuffer.allocateDirect(TEXELS_PER_LAYER * 4);
        for (int i = 0; i < TEXELS_PER_LAYER; i++) {
            int rgb = pixels[i] & 0xffffff;
            // RS model textures commonly encode cut-out/transparent areas as 0.
            // The software rasterizer treats those texels as holes; preserving
            // them as opaque produced the large black wedges seen in foliage.
            int a = (rgb == 0 || rgb == 0xff00ff) ? 0 : 0xff;
            out.put((byte)((rgb >> 16) & 0xff));
            out.put((byte)((rgb >> 8) & 0xff));
            out.put((byte)(rgb & 0xff));
            out.put((byte)a);
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
