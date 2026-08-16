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
        gl.glTexParameteri(GL3.GL_TEXTURE_2D_ARRAY, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR);
        gl.glTexParameteri(GL3.GL_TEXTURE_2D_ARRAY, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL3.GL_TEXTURE_2D_ARRAY, GL.GL_TEXTURE_WRAP_S, GL.GL_REPEAT);
        gl.glTexParameteri(GL3.GL_TEXTURE_2D_ARRAY, GL.GL_TEXTURE_WRAP_T, GL.GL_REPEAT);
        gl.glTexImage3D(GL3.GL_TEXTURE_2D_ARRAY, 0, GL.GL_RGBA8, LAYER_SIZE, LAYER_SIZE, layerCount, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null);

        int uploaded = 0;
        int transparentLayers = 0;
        for (int textureId = 0; textureId < layerCount; textureId++) {
            int[] pixels;
            try { pixels = TextureLoader.getTexturePixels(textureId); } catch (RuntimeException ex) { continue; }
            if (pixels == null || pixels.length < TEXELS_PER_LAYER) continue;

            // This distinction is important. The software rasterizer only treats zero
            // texels as cut-outs when the TextureLoader says this texture supports
            // transparency. For an opaque texture, RGB 0 is a real black texel and
            // must cover geometry behind it rather than becoming a hole.
            boolean supportsAlpha;
            try { supportsAlpha = TextureLoader.getTextureTransparent(textureId); }
            catch (RuntimeException ex) { supportsAlpha = false; }
            if (supportsAlpha) transparentLayers++;

            ByteBuffer rgba = rasterizerPixelsToRgba(pixels, supportsAlpha);
            gl.glTexSubImage3D(GL3.GL_TEXTURE_2D_ARRAY, 0, 0, 0, textureId, LAYER_SIZE, LAYER_SIZE, 1, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, rgba);
            uploaded++;
        }

        gl.glGenerateMipmap(GL3.GL_TEXTURE_2D_ARRAY);
        gl.glBindTexture(GL3.GL_TEXTURE_2D_ARRAY, 0);
        System.out.println("[OPENGL-TEXTURES] GPU texture array ready: " + uploaded + "/" + layerCount
                + " rasterizer textures (" + LAYER_SIZE + "x" + LAYER_SIZE
                + ", RS transparency=" + transparentLayers + " layers, alpha-edge bleed + mipmaps).");
    }

    private ByteBuffer rasterizerPixelsToRgba(int[] pixels, boolean supportsAlpha) {
        int[] rgb = new int[TEXELS_PER_LAYER];
        boolean[] opaque = new boolean[TEXELS_PER_LAYER];

        for (int i = 0; i < TEXELS_PER_LAYER; i++) {
            int value = pixels[i] & 0xffffff;
            rgb[i] = value;
            // Match GameRasterizer.drawTexturedLine: transparent textures skip zero
            // texels; opaque textures write them. 0xff00ff is retained as a cut-out
            // safeguard for sprite-backed textures that expose the legacy key colour.
            opaque[i] = !supportsAlpha || (value != 0 && value != 0xff00ff);
        }

        // Only cut-out textures need RGB dilation beneath alpha=0. Opaque textures
        // must preserve their real black texels exactly.
        int[] bled = rgb.clone();
        if (supportsAlpha) {
            boolean[] known = opaque.clone();
            for (int pass = 0; pass < 4; pass++) {
                int[] next = bled.clone();
                boolean[] nextKnown = known.clone();
                boolean changed = false;
                for (int y = 0; y < LAYER_SIZE; y++) {
                    for (int x = 0; x < LAYER_SIZE; x++) {
                        int i = y * LAYER_SIZE + x;
                        if (known[i]) continue;
                        int count = 0, r = 0, g = 0, b = 0;
                        if (x > 0 && known[i - 1]) { int v=bled[i-1]; r+=(v>>16)&255; g+=(v>>8)&255; b+=v&255; count++; }
                        if (x+1 < LAYER_SIZE && known[i + 1]) { int v=bled[i+1]; r+=(v>>16)&255; g+=(v>>8)&255; b+=v&255; count++; }
                        if (y > 0 && known[i - LAYER_SIZE]) { int v=bled[i-LAYER_SIZE]; r+=(v>>16)&255; g+=(v>>8)&255; b+=v&255; count++; }
                        if (y+1 < LAYER_SIZE && known[i + LAYER_SIZE]) { int v=bled[i+LAYER_SIZE]; r+=(v>>16)&255; g+=(v>>8)&255; b+=v&255; count++; }
                        if (count > 0) {
                            next[i] = ((r/count)<<16)|((g/count)<<8)|(b/count);
                            nextKnown[i] = true;
                            changed = true;
                        }
                    }
                }
                bled = next;
                known = nextKnown;
                if (!changed) break;
            }
        }

        ByteBuffer out = ByteBuffer.allocateDirect(TEXELS_PER_LAYER * 4);
        for (int i = 0; i < TEXELS_PER_LAYER; i++) {
            int value = bled[i];
            int a = opaque[i] ? 0xff : 0;
            out.put((byte)((value >> 16) & 0xff));
            out.put((byte)((value >> 8) & 0xff));
            out.put((byte)(value & 0xff));
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
