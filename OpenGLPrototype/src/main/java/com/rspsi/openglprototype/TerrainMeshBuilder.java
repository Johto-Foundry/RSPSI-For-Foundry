package com.rspsi.openglprototype;

import com.jagex.Client;
import com.jagex.cache.def.Floor;
import com.jagex.cache.loader.floor.FloorDefinitionLoader;
import com.jagex.chunk.Chunk;
import com.jagex.draw.raster.GameRasterizer;
import com.jagex.map.SceneGraph;
import com.jagex.map.tile.SceneTile;
import com.jagex.map.tile.ShapedTile;
import com.jagex.map.tile.SimpleTile;

import java.util.Arrays;

/** Converts RSPSi's already-built SceneGraph terrain into a GPU-ready mesh. */
public final class TerrainMeshBuilder {
    private static final int CHUNK_SIZE = 64;
    private static final float TILE_SIZE = 128.0f;
    private static final int HIDDEN_COLOUR = 0xbc614e;

    private TerrainMeshBuilder() {
    }

    public static TerrainMeshSnapshot build(Chunk chunk, int plane) {
        Client client = Client.getSingleton();
        SceneGraph sceneGraph = client == null ? null : client.sceneGraph;
        if (chunk == null || chunk.mapRegion == null || sceneGraph == null) {
            return new TerrainMeshSnapshot(new float[0], new float[0], new int[0]);
        }

        FloatCollector positions = new FloatCollector(CHUNK_SIZE * CHUNK_SIZE * 18 * 3);
        FloatCollector colours = new FloatCollector(CHUNK_SIZE * CHUNK_SIZE * 18 * 3);
        IntCollector indices = new IntCollector(CHUNK_SIZE * CHUNK_SIZE * 18);

        int fallbackTiles = 0;
        int shapedTiles = 0;
        int simpleTiles = 0;
        int texturedTriangles = 0;

        for (int localY = 0; localY < CHUNK_SIZE; localY++) {
            for (int localX = 0; localX < CHUNK_SIZE; localX++) {
                int mapX = chunk.offsetX + localX;
                int mapY = chunk.offsetY + localY;

                SceneTile tile = null;
                if (plane >= 0 && plane < sceneGraph.tiles.length
                        && mapX >= 0 && mapX < sceneGraph.width
                        && mapY >= 0 && mapY < sceneGraph.length) {
                    tile = sceneGraph.tiles[plane][mapX][mapY];
                }

                ShapedTile shaped = tile == null ? null : tile.temporaryShapedTile.orElse(tile.shape);
                SimpleTile simple = tile == null ? null : tile.temporarySimpleTile.orElse(tile.simple);

                if (shaped != null && shaped.getTriangleA() != null) {
                    texturedTriangles += appendShapedTile(shaped, positions, colours, indices);
                    shapedTiles++;
                } else if (simple != null) {
                    if (appendSimpleTile(chunk, plane, mapX, mapY, simple, positions, colours, indices)) {
                        texturedTriangles += 2;
                    }
                    simpleTiles++;
                } else {
                    appendFallbackTile(chunk, plane, mapX, mapY, positions, colours, indices);
                    fallbackTiles++;
                }
            }
        }

        System.out.println("[OPENGL-TERRAIN] chunk=" + (chunk.offsetX / 64) + "," + (chunk.offsetY / 64)
                + " simple=" + simpleTiles + " shaped=" + shapedTiles + " fallback=" + fallbackTiles
                + " texturedTriangles=" + texturedTriangles + " triangles=" + (indices.size / 3));

        return new TerrainMeshSnapshot(positions.toArray(), colours.toArray(), indices.toArray());
    }

    private static int appendShapedTile(ShapedTile tile,
                                        FloatCollector positions,
                                        FloatCollector colours,
                                        IntCollector indices) {
        int[] xs = tile.getOrigVertexX();
        int[] ys = tile.getOrigVertexY();
        int[] zs = tile.getOrigVertexZ();
        int[] a = tile.getTriangleA();
        int[] b = tile.getTriangleB();
        int[] c = tile.getTriangleC();
        int[] hslA = tile.getTriangleHslA();
        int[] hslB = tile.getTriangleHslB();
        int[] hslC = tile.getTriangleHslC();
        int[] textures = tile.getTriangleTexture();
        int[] displayColours = tile.getDisplayColor();

        if (xs == null || ys == null || zs == null || a == null || b == null || c == null) {
            return 0;
        }

        int texturedTriangles = 0;
        for (int triangle = 0; triangle < a.length; triangle++) {
            int ia = a[triangle];
            int ib = b[triangle];
            int ic = c[triangle];
            if (!validVertex(ia, xs.length) || !validVertex(ib, xs.length) || !validVertex(ic, xs.length)) {
                continue;
            }

            boolean textured = textures != null && triangle < textures.length && textures[triangle] >= 0;
            int triangleFallback = tile.getUnderlayColour();
            if (textured) {
                texturedTriangles++;
                if (displayColours != null && triangle < displayColours.length) {
                    triangleFallback = paletteRgb(displayColours[triangle], tile.getTextureColour());
                } else {
                    triangleFallback = paletteRgb(tile.getTextureColour(), tile.getUnderlayColour());
                }
            }

            int colourA = hslA != null && triangle < hslA.length ? hslA[triangle] : triangleFallback;
            int colourB = hslB != null && triangle < hslB.length ? hslB[triangle] : triangleFallback;
            int colourC = hslC != null && triangle < hslC.length ? hslC[triangle] : triangleFallback;

            int base = positions.size / 3;
            appendVertex(positions, colours, xs[ia], -ys[ia], zs[ia], paletteRgb(colourA, triangleFallback));
            appendVertex(positions, colours, xs[ib], -ys[ib], zs[ib], paletteRgb(colourB, triangleFallback));
            appendVertex(positions, colours, xs[ic], -ys[ic], zs[ic], paletteRgb(colourC, triangleFallback));

            indices.add(base);
            indices.add(base + 1);
            indices.add(base + 2);
        }
        return texturedTriangles;
    }

    private static boolean appendSimpleTile(Chunk chunk, int plane, int mapX, int mapY, SimpleTile tile,
                                            FloatCollector positions,
                                            FloatCollector colours,
                                            IntCollector indices) {
        float x0 = mapX * TILE_SIZE;
        float x1 = (mapX + 1) * TILE_SIZE;
        float z0 = mapY * TILE_SIZE;
        float z1 = (mapY + 1) * TILE_SIZE;

        float h00 = -chunk.mapRegion.tileHeights[plane][mapX][mapY];
        float h10 = -chunk.mapRegion.tileHeights[plane][mapX + 1][mapY];
        float h01 = -chunk.mapRegion.tileHeights[plane][mapX][mapY + 1];
        float h11 = -chunk.mapRegion.tileHeights[plane][mapX + 1][mapY + 1];

        int floorFallback = resolveTileRgb(chunk, plane, mapX, mapY);
        boolean textured = tile.getTexture() >= 0;
        int fallback = textured ? paletteRgb(tile.getColour(), floorFallback) : floorFallback;

        int centre = paletteRgb(tile.getCentreColour(), fallback);
        int east = paletteRgb(tile.getEastColour(), fallback);
        int north = paletteRgb(tile.getNorthColour(), fallback);
        int northEast = paletteRgb(tile.getNorthEastColour(), fallback);

        int base = positions.size / 3;
        appendVertex(positions, colours, x0, h00, z0, centre);
        appendVertex(positions, colours, x0, h01, z1, north);
        appendVertex(positions, colours, x1, h10, z0, east);
        appendVertex(positions, colours, x1, h10, z0, east);
        appendVertex(positions, colours, x0, h01, z1, north);
        appendVertex(positions, colours, x1, h11, z1, northEast);
        for (int i = 0; i < 6; i++) {
            indices.add(base + i);
        }
        return textured;
    }

    private static void appendFallbackTile(Chunk chunk, int plane, int mapX, int mapY,
                                           FloatCollector positions,
                                           FloatCollector colours,
                                           IntCollector indices) {
        float x0 = mapX * TILE_SIZE;
        float x1 = (mapX + 1) * TILE_SIZE;
        float z0 = mapY * TILE_SIZE;
        float z1 = (mapY + 1) * TILE_SIZE;
        float h00 = -chunk.mapRegion.tileHeights[plane][mapX][mapY];
        float h10 = -chunk.mapRegion.tileHeights[plane][mapX + 1][mapY];
        float h01 = -chunk.mapRegion.tileHeights[plane][mapX][mapY + 1];
        float h11 = -chunk.mapRegion.tileHeights[plane][mapX + 1][mapY + 1];
        int rgb = resolveTileRgb(chunk, plane, mapX, mapY);

        int base = positions.size / 3;
        appendVertex(positions, colours, x0, h00, z0, rgb);
        appendVertex(positions, colours, x0, h01, z1, rgb);
        appendVertex(positions, colours, x1, h10, z0, rgb);
        appendVertex(positions, colours, x1, h10, z0, rgb);
        appendVertex(positions, colours, x0, h01, z1, rgb);
        appendVertex(positions, colours, x1, h11, z1, rgb);
        for (int i = 0; i < 6; i++) {
            indices.add(base + i);
        }
    }

    private static void appendVertex(FloatCollector positions, FloatCollector colours,
                                     float x, float y, float z, int rgb) {
        positions.add(x);
        positions.add(y);
        positions.add(z);
        colours.add(((rgb >> 16) & 0xff) / 255.0f);
        colours.add(((rgb >> 8) & 0xff) / 255.0f);
        colours.add((rgb & 0xff) / 255.0f);
    }

    private static boolean validVertex(int index, int length) {
        return index >= 0 && index < length;
    }

    private static int paletteRgb(int hsl, int fallbackRgb) {
        if (hsl == HIDDEN_COLOUR || hsl < 0) {
            return sanitiseRgb(fallbackRgb);
        }
        GameRasterizer rasterizer = GameRasterizer.getInstance();
        if (rasterizer != null && rasterizer.colourPalette != null
                && hsl >= 0 && hsl < rasterizer.colourPalette.length) {
            int rgb = rasterizer.colourPalette[hsl];
            // A valid palette entry of zero is visually indistinguishable from a
            // missing face against the editor background, so use the known tile
            // colour while texture sampling is not wired into OpenGL yet.
            if ((rgb & 0xffffff) != 0) {
                return sanitiseRgb(rgb);
            }
        }
        return sanitiseRgb(fallbackRgb);
    }

    private static int resolveTileRgb(Chunk chunk, int plane, int mapX, int mapY) {
        int overlayValue = chunk.mapRegion.overlays[plane][mapX][mapY] & 0xff;
        if (overlayValue > 0 && FloorDefinitionLoader.instance != null) {
            Floor overlay = FloorDefinitionLoader.getOverlay(overlayValue - 1);
            if (overlay != null) {
                int secondary = overlay.getAnotherRgb();
                if (secondary >= 0 && secondary != 0xff00ff) {
                    return sanitiseRgb(secondary);
                }
                return sanitiseRgb(overlay.getRgb());
            }
        }

        int underlayValue = chunk.mapRegion.underlays[plane][mapX][mapY] & 0xff;
        if (underlayValue > 0 && FloorDefinitionLoader.instance != null) {
            Floor underlay = FloorDefinitionLoader.getUnderlay(underlayValue - 1);
            if (underlay != null) {
                return sanitiseRgb(underlay.getRgb());
            }
        }

        return 0x6f8f43;
    }

    private static int sanitiseRgb(int rgb) {
        if (rgb < 0 || rgb == 0xff00ff || rgb == HIDDEN_COLOUR || (rgb & 0xffffff) == 0) {
            return 0x6f8f43;
        }
        return rgb & 0xffffff;
    }

    private static final class FloatCollector {
        private float[] data;
        private int size;

        private FloatCollector(int capacity) {
            data = new float[Math.max(capacity, 32)];
        }

        private void add(float value) {
            if (size == data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = value;
        }

        private float[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }

    private static final class IntCollector {
        private int[] data;
        private int size;

        private IntCollector(int capacity) {
            data = new int[Math.max(capacity, 32)];
        }

        private void add(int value) {
            if (size == data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = value;
        }

        private int[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }
}
