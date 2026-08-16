package com.rspsi.openglprototype;

import com.jagex.Client;
import com.jagex.chunk.Chunk;
import com.jogamp.newt.event.MouseAdapter;
import com.jogamp.newt.event.MouseEvent;
import com.jogamp.newt.event.WindowAdapter;
import com.jogamp.newt.event.WindowEvent;
import com.jogamp.newt.opengl.GLWindow;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.util.Animator;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Experimental live GPU view of the terrain already loaded by the working
 * RSPSi editor. Editing and saving remain owned by the existing editor while
 * this renderer progressively replaces the software viewport.
 *
 * The default camera mirrors the live RSPSi camera so the GPU window behaves
 * like the existing editor rather than a whole-map inspection view. Holding
 * the right mouse button in this preview temporarily switches to the original
 * orbit camera, which is kept as a useful debugging fallback.
 */
public final class LiveTerrainPreview implements GLEventListener {
    private final Client client;
    private final List<TerrainGpuBuffer> terrain = new ArrayList<>();
    private final SimpleTerrainShader shader = new SimpleTerrainShader();
    private final Matrix4f viewProjection = new Matrix4f();

    private int viewportWidth = 1280;
    private int viewportHeight = 720;

    private float sceneCenterX;
    private float sceneCenterZ;
    private float sceneRadius = 12000.0f;
    private float orbitYaw = 45.0f;
    private float orbitPitch = 42.0f;
    private float orbitZoom = 1.8f;
    private int dragX;
    private int dragY;
    private volatile boolean orbitOverride;

    private long frames;
    private long lastFpsNanos = System.nanoTime();

    private LiveTerrainPreview(Client client) {
        this.client = client;
    }

    public static void open(Client client) {
        if (client == null || client.chunks == null || client.chunks.isEmpty()) {
            System.out.println("[OPENGL] No loaded chunks are available for the terrain preview.");
            return;
        }

        GLProfile.initSingleton();
        GLWindow window = GLWindow.create(new GLCapabilities(GLProfile.get(GLProfile.GL3)));
        LiveTerrainPreview preview = new LiveTerrainPreview(client);
        window.setTitle("RSPSi for Foundry - Live OpenGL Terrain (RSPSi camera)");
        window.setSize(1280, 720);
        window.addGLEventListener(preview);

        Animator animator = new Animator(window);
        animator.setRunAsFastAsPossible(true);

        window.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                preview.dragX = e.getX();
                preview.dragY = e.getY();
                if ((e.getModifiers() & MouseEvent.BUTTON3_MASK) != 0) {
                    preview.orbitOverride = true;
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if ((e.getModifiers() & MouseEvent.BUTTON3_MASK) != 0) {
                    preview.orbitOverride = false;
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (!preview.orbitOverride) {
                    return;
                }
                int dx = e.getX() - preview.dragX;
                int dy = e.getY() - preview.dragY;
                preview.dragX = e.getX();
                preview.dragY = e.getY();
                preview.orbitYaw += dx * 0.35f;
                preview.orbitPitch = clamp(preview.orbitPitch - dy * 0.25f, 8.0f, 82.0f);
            }

            @Override
            public void mouseWheelMoved(MouseEvent e) {
                if (!preview.orbitOverride) {
                    return;
                }
                float[] rotation = e.getRotation();
                if (rotation.length > 1) {
                    preview.orbitZoom = clamp(preview.orbitZoom + rotation[1] * 0.12f, 0.45f, 5.0f);
                }
            }
        });

        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowDestroyed(WindowEvent e) {
                if (animator.isStarted()) {
                    animator.stop();
                }
            }
        });

        window.setVisible(true);
        animator.start();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void init(GLAutoDrawable drawable) {
        GL3 gl = drawable.getGL().getGL3();
        gl.setSwapInterval(0);
        gl.glEnable(GL.GL_DEPTH_TEST);

        // RSPSi's shaped-tile triangle order is not guaranteed to share one GL
        // winding convention after the Jagex -> OpenGL axis conversion. Render
        // terrain double-sided for now so a valid face can never become a black
        // hole simply because GL_BACK culling rejected it. Once every terrain
        // path has a canonical winding we can safely re-enable face culling.
        gl.glDisable(GL.GL_CULL_FACE);

        System.out.println("[OPENGL-LIVE] Renderer: " + gl.glGetString(GL.GL_RENDERER));
        System.out.println("[OPENGL-LIVE] Loaded chunks: " + client.chunks.size());
        System.out.println("[OPENGL-LIVE] Terrain face culling disabled while tile winding is validated.");

        shader.init(gl);
        uploadLoadedTerrain(gl);
    }

    private void uploadLoadedTerrain(GL3 gl) {
        float minX = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        int uploaded = 0;

        int plane = Math.max(0, Math.min(3, client.getPlane()));
        for (Chunk chunk : client.chunks) {
            if (chunk == null || chunk.mapRegion == null) {
                continue;
            }

            TerrainMeshSnapshot mesh = TerrainMeshBuilder.build(chunk, plane);
            if (mesh.getIndices().length == 0) {
                continue;
            }

            TerrainGpuBuffer buffer = new TerrainGpuBuffer();
            buffer.upload(gl, mesh);
            terrain.add(buffer);
            uploaded++;

            float chunkMinX = chunk.offsetX * 128.0f;
            float chunkMinZ = chunk.offsetY * 128.0f;
            float chunkMaxX = (chunk.offsetX + 64) * 128.0f;
            float chunkMaxZ = (chunk.offsetY + 64) * 128.0f;
            minX = Math.min(minX, chunkMinX);
            minZ = Math.min(minZ, chunkMinZ);
            maxX = Math.max(maxX, chunkMaxX);
            maxZ = Math.max(maxZ, chunkMaxZ);
        }

        if (uploaded > 0) {
            sceneCenterX = (minX + maxX) * 0.5f;
            sceneCenterZ = (minZ + maxZ) * 0.5f;
            sceneRadius = Math.max(maxX - minX, maxZ - minZ) * 0.58f;
        }

        System.out.println("[OPENGL-LIVE] GPU terrain chunks uploaded: " + uploaded + " | plane=" + plane);
        System.out.println("[OPENGL-LIVE] Camera now mirrors RSPSi. Move/rotate the normal editor camera to compare views.");
        System.out.println("[OPENGL-LIVE] Hold right mouse in this window for temporary whole-map orbit mode.");
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        GL3 gl = drawable.getGL().getGL3();
        gl.glClearColor(0.035f, 0.045f, 0.055f, 1.0f);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);

        float aspect = viewportHeight <= 0 ? 1.0f : (float) viewportWidth / (float) viewportHeight;
        if (orbitOverride) {
            buildOrbitCamera(aspect);
        } else {
            buildRspsiCamera(aspect);
        }

        shader.use(gl);
        shader.setViewProjection(gl, viewProjection);
        for (TerrainGpuBuffer buffer : terrain) {
            buffer.draw(gl);
        }

        frames++;
        long now = System.nanoTime();
        if (now - lastFpsNanos >= 1_000_000_000L) {
            System.out.println("[OPENGL-LIVE] FPS: " + frames
                    + " | terrainChunks=" + terrain.size()
                    + " | camera=" + (orbitOverride ? "orbit" : "rspsi")
                    + " | pos=" + (client.xCameraPos / 128) + "," + (client.yCameraPos / 128) + "," + client.zCameraPos);
            frames = 0;
            lastFpsNanos = now;
        }
    }

    private void buildRspsiCamera(float aspect) {
        float eyeX = client.xCameraPos;
        float eyeY = -client.zCameraPos;
        float eyeZ = client.yCameraPos;

        float yaw = (float) (client.xCameraCurve * (Math.PI * 2.0 / 2048.0));
        float pitch = (float) (client.yCameraCurve * (Math.PI * 2.0 / 2048.0));
        float cosPitch = (float) Math.cos(pitch);
        float dirX = -(float) Math.sin(yaw) * cosPitch;
        float dirY = -(float) Math.sin(pitch);
        float dirZ = (float) Math.cos(yaw) * cosPitch;

        if (Math.abs(dirX) + Math.abs(dirY) + Math.abs(dirZ) < 0.0001f) {
            dirZ = 1.0f;
        }

        viewProjection.identity()
                .perspective((float) Math.toRadians(55.0), aspect, 16.0f, 150000.0f)
                .lookAt(eyeX, eyeY, eyeZ,
                        eyeX + dirX * 1024.0f,
                        eyeY + dirY * 1024.0f,
                        eyeZ + dirZ * 1024.0f,
                        0.0f, 1.0f, 0.0f);
    }

    private void buildOrbitCamera(float aspect) {
        float distance = sceneRadius * orbitZoom;
        float yawRadians = (float) Math.toRadians(orbitYaw);
        float pitchRadians = (float) Math.toRadians(orbitPitch);
        float horizontal = (float) Math.cos(pitchRadians) * distance;
        float eyeX = sceneCenterX + (float) Math.sin(yawRadians) * horizontal;
        float eyeZ = sceneCenterZ + (float) Math.cos(yawRadians) * horizontal;
        float eyeY = (float) Math.sin(pitchRadians) * distance;

        viewProjection.identity()
                .perspective((float) Math.toRadians(55.0), aspect, 32.0f, Math.max(100000.0f, sceneRadius * 10.0f))
                .lookAt(eyeX, eyeY, eyeZ,
                        sceneCenterX, 0.0f, sceneCenterZ,
                        0.0f, 1.0f, 0.0f);
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
        viewportWidth = Math.max(width, 1);
        viewportHeight = Math.max(height, 1);
        drawable.getGL().getGL3().glViewport(0, 0, viewportWidth, viewportHeight);
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
        GL3 gl = drawable.getGL().getGL3();
        for (TerrainGpuBuffer buffer : terrain) {
            buffer.dispose(gl);
        }
        terrain.clear();
        shader.dispose(gl);
    }
}
