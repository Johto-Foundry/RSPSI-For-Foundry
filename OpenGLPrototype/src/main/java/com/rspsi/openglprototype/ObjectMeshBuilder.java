package com.rspsi.openglprototype;

import com.jagex.Client;
import com.jagex.draw.raster.GameRasterizer;
import com.jagex.entity.Renderable;
import com.jagex.entity.model.Mesh;
import com.jagex.map.SceneGraph;
import com.jagex.map.object.DefaultWorldObject;
import com.jagex.map.object.GameObject;
import com.jagex.map.object.WallDecoration;
import com.jagex.map.tile.SceneTile;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Converts RSPSi's already-resolved scene models into GPU triangles. */
public final class ObjectMeshBuilder {
    private static final float MODEL_TEXTURE_SCALE = 128.0f;
    private ObjectMeshBuilder() {}

    public static TerrainMeshSnapshot build(int plane) {
        Client client = Client.getSingleton();
        SceneGraph graph = client == null ? null : client.sceneGraph;
        if (graph == null || graph.tiles == null || plane < 0 || plane >= graph.tiles.length) return empty();

        FloatCollector positions = new FloatCollector(262144);
        FloatCollector colours = new FloatCollector(262144);
        FloatCollector uvs = new FloatCollector(131072);
        FloatCollector textureIds = new FloatCollector(65536);
        IntCollector indices = new IntCollector(131072);
        Set<DefaultWorldObject> seen = Collections.newSetFromMap(new IdentityHashMap<DefaultWorldObject, Boolean>());

        Stats stats = new Stats();
        for (int x = 0; x < graph.width; x++) for (int y = 0; y < graph.length; y++) {
            SceneTile tile = graph.tiles[plane][x][y];
            if (tile == null) continue;
            if (tile.temporaryObject.isPresent()) {
                DefaultWorldObject o = tile.temporaryObject.get();
                if (o != null && seen.add(o)) appendObject(o, positions, colours, uvs, textureIds, indices, stats);
            }
            for (DefaultWorldObject o : tile.getExistingObjects()) {
                if (o != null && seen.add(o)) appendObject(o, positions, colours, uvs, textureIds, indices, stats);
            }
        }

        System.out.println("[OPENGL-OBJECTS] objects=" + stats.objects
                + " meshes=" + stats.meshes
                + " faces=" + stats.faces
                + " texturedFaces=" + stats.texturedFaces
                + " alphaSkipped=" + stats.alphaSkipped
                + " triangles=" + (indices.size / 3));
        return new TerrainMeshSnapshot(positions.toArray(), colours.toArray(), uvs.toArray(), textureIds.toArray(), indices.toArray());
    }

    private static void appendObject(DefaultWorldObject object, FloatCollector p, FloatCollector c,
                                     FloatCollector uv, FloatCollector tex, IntCollector idx, Stats stats) {
        stats.objects++;
        int orientation = orientationFor(object);
        int worldX = object instanceof GameObject ? ((GameObject) object).centreX : object.getX();
        int worldZ = object instanceof GameObject ? ((GameObject) object).centreY : object.getY();
        appendRenderable(object.getPrimary(), worldX, worldZ, object.getRenderHeight(), orientation, p, c, uv, tex, idx, stats);
        appendRenderable(object.getSecondary(), worldX, worldZ, object.getRenderHeight(), orientation, p, c, uv, tex, idx, stats);
    }

    private static int orientationFor(DefaultWorldObject object) {
        if (object instanceof GameObject) return ((GameObject) object).yaw & 0x7ff;
        if (object instanceof WallDecoration) return ((WallDecoration) object).getOrientation() & 0x7ff;
        return 0;
    }

    private static Mesh resolveMesh(Renderable r) {
        if (r == null) return null;
        if (r instanceof Mesh) return (Mesh) r;
        try { return r.model(); } catch (RuntimeException ex) { return null; }
    }

    private static void appendRenderable(Renderable renderable, int worldX, int worldZ, int renderHeight, int orientation,
                                         FloatCollector p, FloatCollector col, FloatCollector uv, FloatCollector tex,
                                         IntCollector ind, Stats stats) {
        Mesh m = resolveMesh(renderable);
        if (m == null || m.verticesX == null || m.verticesY == null || m.verticesZ == null
                || m.faceIndicesA == null || m.faceIndicesB == null || m.faceIndicesC == null) return;
        stats.meshes++;
        int vc = Math.min(m.numVertices, Math.min(m.verticesX.length, Math.min(m.verticesY.length, m.verticesZ.length)));
        int fc = Math.min(m.numFaces, Math.min(m.faceIndicesA.length, Math.min(m.faceIndicesB.length, m.faceIndicesC.length)));
        if (vc <= 0 || fc <= 0) return;

        double radians = orientation * (Math.PI * 2.0 / 2048.0);
        float sin = (float)Math.sin(radians), cos = (float)Math.cos(radians);

        for (int face = 0; face < fc; face++) {
            if (m.faceTypes != null && face < m.faceTypes.length && m.faceTypes[face] == -1) continue;
            // RS model alpha uses 0 as opaque and 255 as fully transparent. Do not
            // depth-write faces that the software renderer would effectively hide.
            if (m.faceAlphas != null && face < m.faceAlphas.length && (m.faceAlphas[face] & 0xff) >= 250) {
                stats.alphaSkipped++;
                continue;
            }
            int a=m.faceIndicesA[face], b=m.faceIndicesB[face], cc=m.faceIndicesC[face];
            if(!valid(a,vc)||!valid(b,vc)||!valid(cc,vc)) continue;

            int textureId = (m.faceTextures != null && face < m.faceTextures.length) ? m.faceTextures[face] : -1;
            boolean textured = textureId >= 0;
            if (textured) stats.texturedFaces++;
            stats.faces++;

            int[] projection = chooseProjection(m,a,b,cc);
            int base=p.size/3;
            appendVertex(m,a,worldX,worldZ,renderHeight,sin,cos,faceColour(m,face,0,textured),textureId,projection,p,col,uv,tex);
            appendVertex(m,b,worldX,worldZ,renderHeight,sin,cos,faceColour(m,face,1,textured),textureId,projection,p,col,uv,tex);
            appendVertex(m,cc,worldX,worldZ,renderHeight,sin,cos,faceColour(m,face,2,textured),textureId,projection,p,col,uv,tex);
            ind.add(base);ind.add(base+1);ind.add(base+2);
        }
    }

    /** Dominant-axis planar UV projection: a practical first pass until exact RS texture mapping is ported. */
    private static int[] chooseProjection(Mesh m,int a,int b,int c){
        long ux=m.verticesX[b]-m.verticesX[a], uy=m.verticesY[b]-m.verticesY[a], uz=m.verticesZ[b]-m.verticesZ[a];
        long vx=m.verticesX[c]-m.verticesX[a], vy=m.verticesY[c]-m.verticesY[a], vz=m.verticesZ[c]-m.verticesZ[a];
        long nx=Math.abs(uy*vz-uz*vy), ny=Math.abs(uz*vx-ux*vz), nz=Math.abs(ux*vy-uy*vx);
        if(nx>=ny&&nx>=nz)return new int[]{1,2}; // Y/Z
        if(ny>=nz)return new int[]{0,2};         // X/Z
        return new int[]{0,1};                   // X/Y
    }

    private static void appendVertex(Mesh m,int v,int worldX,int worldZ,int renderHeight,float sin,float cos,int rgb,
                                     int textureId,int[] projection,FloatCollector p,FloatCollector col,FloatCollector uv,FloatCollector tex){
        float lx=m.verticesX[v], ly=m.verticesY[v], lz=m.verticesZ[v];
        float rx=lx*cos+lz*sin, rz=lz*cos-lx*sin;
        p.add(worldX+rx);p.add(-renderHeight-ly);p.add(worldZ+rz);
        col.add(((rgb>>16)&255)/255f);col.add(((rgb>>8)&255)/255f);col.add((rgb&255)/255f);
        float[] q={lx,ly,lz}; uv.add(q[projection[0]]/MODEL_TEXTURE_SCALE);uv.add(q[projection[1]]/MODEL_TEXTURE_SCALE);
        tex.add(textureId);
    }

    private static int faceColour(Mesh m,int face,int corner,boolean textured){
        int hsl=-1;
        if(corner==0&&m.shadedFaceColoursX!=null&&face<m.shadedFaceColoursX.length)hsl=m.shadedFaceColoursX[face];
        if(corner==1&&m.shadedFaceColoursY!=null&&face<m.shadedFaceColoursY.length)hsl=m.shadedFaceColoursY[face];
        if(corner==2&&m.shadedFaceColoursZ!=null&&face<m.shadedFaceColoursZ.length)hsl=m.shadedFaceColoursZ[face];

        if (textured) {
            // For textured RS faces these values are lighting intensities, not HSL
            // palette indices. Feeding them through colourPalette caused many
            // textured pieces (foliage/architecture) to become solid white.
            int light = hsl < 0 ? 96 : Math.max(0, Math.min(127, hsl));
            int v = Math.max(80, Math.min(255, 96 + light));
            return (v<<16)|(v<<8)|v;
        }

        if(hsl<0&&m.faceColours!=null&&face<m.faceColours.length)hsl=m.faceColours[face];
        GameRasterizer r=GameRasterizer.getInstance();
        if(hsl>=0&&r!=null&&r.colourPalette!=null&&hsl<r.colourPalette.length){int rgb=r.colourPalette[hsl]&0xffffff;if(rgb!=0)return rgb;}
        return 0x9b927b;
    }

    private static boolean valid(int i,int n){return i>=0&&i<n;}
    private static TerrainMeshSnapshot empty(){return new TerrainMeshSnapshot(new float[0],new float[0],new float[0],new float[0],new int[0]);}

    private static final class Stats { int objects,meshes,faces,texturedFaces,alphaSkipped; }
    private static final class FloatCollector{private float[] data;private int size;FloatCollector(int cap){data=new float[Math.max(32,cap)];}void add(float v){if(size==data.length)data=Arrays.copyOf(data,data.length*2);data[size++]=v;}float[] toArray(){return Arrays.copyOf(data,size);}}
    private static final class IntCollector{private int[] data;private int size;IntCollector(int cap){data=new int[Math.max(32,cap)];}void add(int v){if(size==data.length)data=Arrays.copyOf(data,data.length*2);data[size++]=v;}int[] toArray(){return Arrays.copyOf(data,size);}}
}
