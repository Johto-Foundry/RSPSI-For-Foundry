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

/** Experimental live GPU view of RSPSi's already-loaded scene. */
public final class LiveTerrainPreview implements GLEventListener {
    private final Client client;
    private final List<TerrainGpuBuffer> terrain = new ArrayList<>();
    private TerrainGpuBuffer objects;
    private final SimpleTerrainShader shader = new SimpleTerrainShader();
    private final Matrix4f viewProjection = new Matrix4f();

    private int viewportWidth = 1280;
    private int viewportHeight = 720;
    private float sceneCenterX, sceneCenterZ, sceneRadius = 12000.0f;
    private float orbitYaw = 45.0f, orbitPitch = 42.0f, orbitZoom = 1.8f;
    private int dragX, dragY;
    private volatile boolean orbitOverride;
    private long frames, lastFpsNanos = System.nanoTime();

    private LiveTerrainPreview(Client client) { this.client = client; }

    public static void open(Client client) {
        if (client == null || client.chunks == null || client.chunks.isEmpty()) {
            System.out.println("[OPENGL] No loaded chunks are available for the scene preview.");
            return;
        }
        GLProfile.initSingleton();
        GLWindow window = GLWindow.create(new GLCapabilities(GLProfile.get(GLProfile.GL3)));
        LiveTerrainPreview preview = new LiveTerrainPreview(client);
        window.setTitle("RSPSi for Foundry - Live OpenGL Scene (RSPSi camera)");
        window.setSize(1280, 720);
        window.addGLEventListener(preview);
        Animator animator = new Animator(window);
        animator.setRunAsFastAsPossible(true);

        window.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                preview.dragX=e.getX(); preview.dragY=e.getY();
                if((e.getModifiers()&MouseEvent.BUTTON3_MASK)!=0) preview.orbitOverride=true;
            }
            @Override public void mouseReleased(MouseEvent e) {
                if((e.getModifiers()&MouseEvent.BUTTON3_MASK)!=0) preview.orbitOverride=false;
            }
            @Override public void mouseDragged(MouseEvent e) {
                if(!preview.orbitOverride)return;
                int dx=e.getX()-preview.dragX,dy=e.getY()-preview.dragY;
                preview.dragX=e.getX();preview.dragY=e.getY();
                preview.orbitYaw+=dx*0.35f;
                preview.orbitPitch=clamp(preview.orbitPitch-dy*0.25f,8.0f,82.0f);
            }
            @Override public void mouseWheelMoved(MouseEvent e) {
                if(!preview.orbitOverride)return;
                float[] r=e.getRotation();
                if(r.length>1)preview.orbitZoom=clamp(preview.orbitZoom+r[1]*0.12f,0.45f,5.0f);
            }
        });
        window.addWindowListener(new WindowAdapter(){@Override public void windowDestroyed(WindowEvent e){if(animator.isStarted())animator.stop();}});
        window.setVisible(true); animator.start();
    }

    private static float clamp(float v,float min,float max){return Math.max(min,Math.min(max,v));}

    @Override public void init(GLAutoDrawable drawable){
        GL3 gl=drawable.getGL().getGL3(); gl.setSwapInterval(0); gl.glEnable(GL.GL_DEPTH_TEST); gl.glDisable(GL.GL_CULL_FACE);
        System.out.println("[OPENGL-LIVE] Renderer: "+gl.glGetString(GL.GL_RENDERER));
        System.out.println("[OPENGL-LIVE] Loaded chunks: "+client.chunks.size());
        shader.init(gl); uploadLoadedScene(gl);
    }

    private void uploadLoadedScene(GL3 gl){
        float minX=Float.POSITIVE_INFINITY,minZ=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,maxZ=Float.NEGATIVE_INFINITY; int uploaded=0;
        int plane=Math.max(0,Math.min(3,client.getPlane()));
        for(Chunk chunk:client.chunks){
            if(chunk==null||chunk.mapRegion==null)continue;
            TerrainMeshSnapshot mesh=TerrainMeshBuilder.build(chunk,plane); if(mesh.getIndices().length==0)continue;
            TerrainGpuBuffer buffer=new TerrainGpuBuffer();buffer.upload(gl,mesh);terrain.add(buffer);uploaded++;
            float cminx=chunk.offsetX*128.0f,cminz=chunk.offsetY*128.0f,cmaxx=(chunk.offsetX+64)*128.0f,cmaxz=(chunk.offsetY+64)*128.0f;
            minX=Math.min(minX,cminx);minZ=Math.min(minZ,cminz);maxX=Math.max(maxX,cmaxx);maxZ=Math.max(maxZ,cmaxz);
        }
        if(uploaded>0){sceneCenterX=(minX+maxX)*0.5f;sceneCenterZ=(minZ+maxZ)*0.5f;sceneRadius=Math.max(maxX-minX,maxZ-minZ)*0.58f;}

        TerrainMeshSnapshot objectMesh=ObjectMeshBuilder.build(plane);
        ObjectRenderDiagnostics.run(plane);
        if(objectMesh.getIndices().length>0){
            objects=new TerrainGpuBuffer();
            objects.upload(gl,objectMesh);
        }

        System.out.println("[OPENGL-LIVE] GPU terrain chunks uploaded: "+uploaded+" | plane="+plane);
        System.out.println("[OPENGL-LIVE] GPU object pass: "+(objects==null?"empty":"uploaded")+" (RSPSi projected face visibility enabled).");
        System.out.println("[OPENGL-LIVE] Camera follows RSPSi with horizontal mirror correction.");
        System.out.println("[OPENGL-LIVE] RSPSi 50-unit near plane is used for the mirrored editor camera.");
        System.out.println("[OPENGL-LIVE] Max-pitch endpoint is clamped one camera unit for stability.");
    }

    @Override public void display(GLAutoDrawable drawable){
        GL3 gl=drawable.getGL().getGL3(); gl.glClearColor(0.035f,0.045f,0.055f,1.0f); gl.glClear(GL.GL_COLOR_BUFFER_BIT|GL.GL_DEPTH_BUFFER_BIT);
        float aspect=viewportHeight<=0?1.0f:(float)viewportWidth/(float)viewportHeight;
        CameraSnapshot camera=null;
        if(orbitOverride)buildOrbitCamera(aspect);else{camera=readStableCamera();buildRspsiCamera(aspect,camera);}
        if(!orbitOverride)ObjectRenderDiagnostics.evaluateVisibility(viewProjection);
        shader.use(gl);shader.setViewProjection(gl,viewProjection);
        shader.setObjectPass(gl,false);
        for(TerrainGpuBuffer b:terrain)b.draw(gl);
        if(objects!=null){shader.setObjectPass(gl,true);objects.draw(gl);shader.setObjectPass(gl,false);}
        frames++;long now=System.nanoTime();if(now-lastFpsNanos>=1_000_000_000L){
            String pos=camera==null?(client.xCameraPos/128)+","+(client.yCameraPos/128)+","+client.zCameraPos:(camera.x/128)+","+(camera.z/128)+","+camera.height;
            System.out.println("[OPENGL-LIVE] FPS: "+frames+" | terrainChunks="+terrain.size()+" | objects="+(objects==null?0:1)+" | camera="+(orbitOverride?"orbit":"rspsi")+" | pos="+pos+" | pitch="+(camera==null?client.yCameraCurve:camera.pitch));
            frames=0;lastFpsNanos=now;
        }
    }

    private CameraSnapshot readStableCamera(){
        CameraSnapshot last=captureCamera();for(int i=0;i<4;i++){CameraSnapshot next=captureCamera();if(last.sameAs(next))return next;last=next;}return last;
    }
    private CameraSnapshot captureCamera(){return new CameraSnapshot(client.xCameraPos,client.yCameraPos,client.zCameraPos,client.xCameraCurve&0x7ff,client.yCameraCurve&0x7ff);}

    private void buildRspsiCamera(float aspect,CameraSnapshot camera){
        float eyeX=camera.x,eyeY=-camera.height,eyeZ=camera.z;
        int stablePitch=camera.pitch>=383?382:camera.pitch;
        float yaw=(float)(camera.yaw*(Math.PI*2.0/2048.0));
        float pitch=(float)(stablePitch*(Math.PI*2.0/2048.0));
        float sinYaw=(float)Math.sin(yaw),cosYaw=(float)Math.cos(yaw),sinPitch=(float)Math.sin(pitch),cosPitch=(float)Math.cos(pitch);
        float dirX=-sinYaw*cosPitch,dirY=-sinPitch,dirZ=cosYaw*cosPitch;
        float upX=-sinYaw*sinPitch,upY=cosPitch,upZ=cosYaw*sinPitch;
        viewProjection.identity().perspective((float)Math.toRadians(55.0),aspect,50.0f,150000.0f).scale(-1.0f,1.0f,1.0f)
                .lookAt(eyeX,eyeY,eyeZ,eyeX+dirX*1024.0f,eyeY+dirY*1024.0f,eyeZ+dirZ*1024.0f,upX,upY,upZ);
    }

    private void buildOrbitCamera(float aspect){
        float distance=sceneRadius*orbitZoom,yaw=(float)Math.toRadians(orbitYaw),pitch=(float)Math.toRadians(orbitPitch),horizontal=(float)Math.cos(pitch)*distance;
        float eyeX=sceneCenterX+(float)Math.sin(yaw)*horizontal,eyeZ=sceneCenterZ+(float)Math.cos(yaw)*horizontal,eyeY=(float)Math.sin(pitch)*distance;
        viewProjection.identity().perspective((float)Math.toRadians(55.0),aspect,32.0f,Math.max(100000.0f,sceneRadius*10.0f))
                .lookAt(eyeX,eyeY,eyeZ,sceneCenterX,0.0f,sceneCenterZ,0.0f,1.0f,0.0f);
    }

    @Override public void reshape(GLAutoDrawable drawable,int x,int y,int width,int height){viewportWidth=Math.max(width,1);viewportHeight=Math.max(height,1);drawable.getGL().getGL3().glViewport(0,0,viewportWidth,viewportHeight);}
    @Override public void dispose(GLAutoDrawable drawable){
        GL3 gl=drawable.getGL().getGL3();for(TerrainGpuBuffer b:terrain)b.dispose(gl);terrain.clear();
        if(objects!=null){objects.dispose(gl);objects=null;}
        shader.dispose(gl);
    }

    private static final class CameraSnapshot{
        final int x,z,height,yaw,pitch;
        CameraSnapshot(int x,int z,int height,int yaw,int pitch){this.x=x;this.z=z;this.height=height;this.yaw=yaw;this.pitch=pitch;}
        boolean sameAs(CameraSnapshot o){return o!=null&&x==o.x&&z==o.z&&height==o.height&&yaw==o.yaw&&pitch==o.pitch;}
    }
}
