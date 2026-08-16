package com.rspsi.openglprototype;

import com.jogamp.newt.opengl.GLWindow;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.util.Animator;

/**
 * First Foundry OpenGL milestone.
 *
 * This deliberately lives beside the existing software renderer so the current
 * editor remains usable while the GPU renderer is brought up incrementally.
 */
public final class FoundryGLPrototype implements GLEventListener {
    private long frames;
    private long lastFpsNanos = System.nanoTime();

    public static void main(String[] args) {
        GLProfile.initSingleton();
        GLProfile profile = GLProfile.get(GLProfile.GL3);
        GLWindow window = GLWindow.create(new GLCapabilities(profile));
        window.setTitle("RSPSi for Foundry - OpenGL Prototype");
        window.setSize(1280, 720);
        window.addGLEventListener(new FoundryGLPrototype());
        window.setVisible(true);

        Animator animator = new Animator(window);
        animator.setRunAsFastAsPossible(true);
        animator.start();
    }

    @Override
    public void init(GLAutoDrawable drawable) {
        GL3 gl = drawable.getGL().getGL3();
        gl.setSwapInterval(0);
        gl.glEnable(GL.GL_DEPTH_TEST);
        System.out.println("[OPENGL] Renderer: " + gl.glGetString(GL.GL_RENDERER));
        System.out.println("[OPENGL] Vendor: " + gl.glGetString(GL.GL_VENDOR));
        System.out.println("[OPENGL] Version: " + gl.glGetString(GL.GL_VERSION));
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        GL3 gl = drawable.getGL().getGL3();
        gl.glClearColor(0.055f, 0.065f, 0.08f, 1f);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);

        frames++;
        long now = System.nanoTime();
        if (now - lastFpsNanos >= 1_000_000_000L) {
            System.out.println("[OPENGL] FPS: " + frames);
            frames = 0;
            lastFpsNanos = now;
        }
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
        drawable.getGL().getGL3().glViewport(0, 0, width, height);
    }
}
