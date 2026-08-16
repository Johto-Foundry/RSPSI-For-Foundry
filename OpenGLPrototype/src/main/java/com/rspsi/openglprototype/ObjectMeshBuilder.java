package com.rspsi.openglprototype;

import com.jagex.Client;
import com.jagex.cache.loader.textures.TextureLoader;
import com.jagex.draw.raster.GameRasterizer;
import com.jagex.entity.Renderable;
import com.jagex.entity.model.Mesh;
import com.jagex.map.SceneGraph;
import com.jagex.map.object.DefaultWorldObject;
import com.jagex.map.object.GameObject;
import com.jagex.map.object.WallDecoration;
import com.jagex.map.tile.SceneTile;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Converts RSPSi's already-resolved scene models into GPU triangles. */
public final class ObjectMeshBuilder {
    private static final Field TEXTURE_COORDINATES_FIELD = findTextureCoordinatesField();
    private static final float MODEL_TEXTURE_MARKER = 1000.0f;

    private ObjectMeshBuilder() {}

    private static Field findTextureCoordinatesField() {
        try {
            Field field = Mesh.class.getDeclaredField("texture_coordinates");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ex) {
            System.out.println("[OPENGL-OBJECTS] Could not access Mesh texture coordinates; exact mapped UVs disabled.");
            return null;
        }
    }

    private static byte[] textureCoordinates(Mesh mesh) {
        if (TEXTURE_COORDINATES_FIELD == null || mesh == null) return null;
        try { return (byte[]) TEXTURE_COORDINATES_FIELD.get(mesh); }
        catch (IllegalAccessException | ClassCastException ex) { return null; }
    }

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

        for (int x=0;x<graph.width;x++) for (int y=0;y<graph.length;y++) {
            SceneTile tile = graph.tiles[plane][x][y];
            if (tile == null) continue;
            if (tile.temporaryObject.isPresent()) {
                DefaultWorldObject o = tile.temporaryObject.get();
                if (o != null && seen.add(o)) appendObject(o,positions,colours,uvs,textureIds,indices,stats);
            }
            for (DefaultWorldObject o : tile.getExistingObjects()) {
                if (o != null && seen.add(o)) appendObject(o,positions,colours,uvs,textureIds,indices,stats);
            }
        }

        System.out.println("[OPENGL-OBJECTS] objects="+stats.objects+" meshes="+stats.meshes+" faces="+stats.faces
                +" texturedFaces="+stats.texturedFaces+" mappedFaces="+stats.mappedFaces
                +" flatFaces="+stats.flatFaces+" flatTexturedFaces="+stats.flatTexturedFaces
                +" missingTextureFaces="+stats.missingTextureFaces+" alphaSkipped="+stats.alphaSkipped
                +" triangles="+(indices.size/3));
        if (!stats.missingTextureIds.isEmpty()) System.out.println("[OPENGL-OBJECTS] Missing texture fallbacks: "+stats.missingTextureIds);
        if (!stats.unmappedByObject.isEmpty()) System.out.println("[OPENGL-OBJECTS] Unmapped textured faces by object (first seen IDs): "+stats.unmappedByObject);
        return new TerrainMeshSnapshot(positions.toArray(),colours.toArray(),uvs.toArray(),textureIds.toArray(),indices.toArray());
    }

    private static void appendObject(DefaultWorldObject object, FloatCollector p, FloatCollector c,
                                     FloatCollector uv, FloatCollector tex, IntCollector idx, Stats stats) {
        stats.objects++;
        int orientation = orientationFor(object);
        int worldX = object instanceof GameObject ? ((GameObject)object).centreX : object.getX();
        int worldZ = object instanceof GameObject ? ((GameObject)object).centreY : object.getY();
        appendRenderable(object.getPrimary(),object.getId(),worldX,worldZ,object.getRenderHeight(),orientation,p,c,uv,tex,idx,stats);
        appendRenderable(object.getSecondary(),object.getId(),worldX,worldZ,object.getRenderHeight(),orientation,p,c,uv,tex,idx,stats);
    }

    private static int orientationFor(DefaultWorldObject object) {
        if (object instanceof GameObject) return ((GameObject)object).yaw & 0x7ff;
        if (object instanceof WallDecoration) return ((WallDecoration)object).getOrientation() & 0x7ff;
        return 0;
    }

    private static Mesh resolveMesh(Renderable r) {
        if (r == null) return null;
        if (r instanceof Mesh) return (Mesh)r;
        try { return r.model(); } catch (RuntimeException ex) { return null; }
    }

    private static void appendRenderable(Renderable renderable,int objectId,int worldX,int worldZ,int renderHeight,int orientation,
                                         FloatCollector p,FloatCollector col,FloatCollector uv,FloatCollector tex,
                                         IntCollector ind,Stats stats) {
        Mesh m=resolveMesh(renderable);
        if(m==null||m.verticesX==null||m.verticesY==null||m.verticesZ==null||m.faceIndicesA==null||m.faceIndicesB==null||m.faceIndicesC==null)return;
        stats.meshes++;
        int vc=Math.min(m.numVertices,Math.min(m.verticesX.length,Math.min(m.verticesY.length,m.verticesZ.length)));
        int fc=Math.min(m.numFaces,Math.min(m.faceIndicesA.length,Math.min(m.faceIndicesB.length,m.faceIndicesC.length)));
        if(vc<=0||fc<=0)return;

        double radians=orientation*(Math.PI*2.0/2048.0);
        float sin=(float)Math.sin(radians),cos=(float)Math.cos(radians);

        for(int face=0;face<fc;face++){
            if(m.faceTypes!=null&&face<m.faceTypes.length&&m.faceTypes[face]==-1)continue;
            if(m.faceAlphas!=null&&face<m.faceAlphas.length&&(m.faceAlphas[face]&0xff)>=250){stats.alphaSkipped++;continue;}
            int a=m.faceIndicesA[face],b=m.faceIndicesB[face],c=m.faceIndicesC[face];
            if(!valid(a,vc)||!valid(b,vc)||!valid(c,vc))continue;

            int requestedTextureId=(m.faceTextures!=null&&face<m.faceTextures.length)?m.faceTextures[face]:-1;
            int type = m.faceTypes == null ? (requestedTextureId >= 0 ? 2 : 0) : (m.faceTypes[face] & 3);
            boolean wantsTexture=(type==2||type==3) && requestedTextureId>=0;
            boolean textureAvailable=wantsTexture && TextureLoader.instance!=null && requestedTextureId<TextureLoader.instance.count() && TextureLoader.getTexture(requestedTextureId)!=null;
            boolean textured=wantsTexture && textureAvailable;
            boolean flat=(type==1||type==3);
            if(wantsTexture&&!textureAvailable){
                stats.missingTextureFaces++;
                stats.missingTextureIds.merge(requestedTextureId,1,Integer::sum);
            }
            if(textured)stats.texturedFaces++;
            if(flat)stats.flatFaces++;
            if(type==3)stats.flatTexturedFaces++;
            stats.faces++;

            float[][] faceUv = textured ? mappedUvs(m,face,a,b,c,vc) : null;
            if(faceUv!=null)stats.mappedFaces++;
            else if(textured){
                // RSPSi falls back to the face's own A/B/C vertices as the texture
                // mapping triangle when no explicit texture-coordinate index exists.
                faceUv=new float[][]{basisUv(m,a,b,c,a),basisUv(m,a,b,c,b),basisUv(m,a,b,c,c)};
                if(stats.unmappedByObject.size()<16||stats.unmappedByObject.containsKey(objectId))
                    stats.unmappedByObject.merge(objectId,1,Integer::sum);
            }
            if(faceUv==null)faceUv=new float[][]{{0,0},{1,0},{0,1}};

            int rgbA=faceColour(m,face,0,textured,flat);
            int rgbB=faceColour(m,face,1,textured,flat);
            int rgbC=faceColour(m,face,2,textured,flat);
            float gpuTextureId=textured ? MODEL_TEXTURE_MARKER+requestedTextureId : -1.0f;

            int base=p.size/3;
            appendVertex(m,a,worldX,worldZ,renderHeight,sin,cos,rgbA,gpuTextureId,faceUv[0],p,col,uv,tex);
            appendVertex(m,b,worldX,worldZ,renderHeight,sin,cos,rgbB,gpuTextureId,faceUv[1],p,col,uv,tex);
            appendVertex(m,c,worldX,worldZ,renderHeight,sin,cos,rgbC,gpuTextureId,faceUv[2],p,col,uv,tex);
            ind.add(base);ind.add(base+1);ind.add(base+2);
        }
    }

    private static float[][] mappedUvs(Mesh m,int face,int a,int b,int c,int vertexCount){
        byte[] coordinates = textureCoordinates(m);
        if(coordinates==null||face>=coordinates.length||coordinates[face]==-1
                ||m.textureMappingP==null||m.textureMappingM==null||m.textureMappingN==null)return null;
        int t=coordinates[face]&0xff;
        if(t>=m.textureMappingP.length||t>=m.textureMappingM.length||t>=m.textureMappingN.length)return null;
        int p=m.textureMappingP[t],q=m.textureMappingM[t],r=m.textureMappingN[t];
        if(!valid(p,vertexCount)||!valid(q,vertexCount)||!valid(r,vertexCount))return null;
        return new float[][]{basisUv(m,p,q,r,a),basisUv(m,p,q,r,b),basisUv(m,p,q,r,c)};
    }

    private static float[] basisUv(Mesh m,int p,int q,int r,int v){
        double e1x=m.verticesX[q]-m.verticesX[p],e1y=m.verticesY[q]-m.verticesY[p],e1z=m.verticesZ[q]-m.verticesZ[p];
        double e2x=m.verticesX[r]-m.verticesX[p],e2y=m.verticesY[r]-m.verticesY[p],e2z=m.verticesZ[r]-m.verticesZ[p];
        double dx=m.verticesX[v]-m.verticesX[p],dy=m.verticesY[v]-m.verticesY[p],dz=m.verticesZ[v]-m.verticesZ[p];
        double d00=e1x*e1x+e1y*e1y+e1z*e1z;
        double d01=e1x*e2x+e1y*e2y+e1z*e2z;
        double d11=e2x*e2x+e2y*e2y+e2z*e2z;
        double d20=dx*e1x+dy*e1y+dz*e1z;
        double d21=dx*e2x+dy*e2y+dz*e2z;
        double den=d00*d11-d01*d01;
        if(Math.abs(den)<1e-8)return new float[]{0,0};
        float u=(float)((d20*d11-d21*d01)/den);
        float vv=(float)((d21*d00-d20*d01)/den);
        return new float[]{u,vv};
    }

    private static void appendVertex(Mesh m,int v,int worldX,int worldZ,int renderHeight,float sin,float cos,int rgb,
                                     float textureId,float[] faceUv,FloatCollector p,FloatCollector col,FloatCollector uv,FloatCollector tex){
        float lx=m.verticesX[v],ly=m.verticesY[v],lz=m.verticesZ[v];
        float rx=lx*cos+lz*sin,rz=lz*cos-lx*sin;
        p.add(worldX+rx);p.add(-renderHeight-ly);p.add(worldZ+rz);
        col.add(((rgb>>16)&255)/255f);col.add(((rgb>>8)&255)/255f);col.add((rgb&255)/255f);
        uv.add(faceUv[0]);uv.add(faceUv[1]);tex.add(textureId);
    }

    private static int faceColour(Mesh m,int face,int corner,boolean textured,boolean flat){
        int shade=-1;
        if(m.shadedFaceColoursX!=null&&face<m.shadedFaceColoursX.length)shade=m.shadedFaceColoursX[face];
        if(!flat){
            if(corner==1&&m.shadedFaceColoursY!=null&&face<m.shadedFaceColoursY.length)shade=m.shadedFaceColoursY[face];
            if(corner==2&&m.shadedFaceColoursZ!=null&&face<m.shadedFaceColoursZ.length)shade=m.shadedFaceColoursZ[face];
        }
        if(textured){
            int s=shade<0?0:Math.max(0,Math.min(127,shade));
            int v=Math.round((s/127.0f)*255.0f);
            return(v<<16)|(v<<8)|v;
        }
        if(shade<0&&m.faceColours!=null&&face<m.faceColours.length)shade=m.faceColours[face];
        GameRasterizer r=GameRasterizer.getInstance();
        if(shade>=0&&r!=null&&r.colourPalette!=null&&shade<r.colourPalette.length){int rgb=r.colourPalette[shade]&0xffffff;if(rgb!=0)return rgb;}
        return 0x9b927b;
    }

    private static boolean valid(int i,int n){return i>=0&&i<n;}
    private static TerrainMeshSnapshot empty(){return new TerrainMeshSnapshot(new float[0],new float[0],new float[0],new float[0],new int[0]);}
    private static final class Stats{
        int objects,meshes,faces,texturedFaces,mappedFaces,flatFaces,flatTexturedFaces,missingTextureFaces,alphaSkipped;
        final Map<Integer,Integer> missingTextureIds=new HashMap<>();
        final Map<Integer,Integer> unmappedByObject=new HashMap<>();
    }
    private static final class FloatCollector{private float[]data;private int size;FloatCollector(int cap){data=new float[Math.max(32,cap)];}void add(float v){if(size==data.length)data=Arrays.copyOf(data,data.length*2);data[size++]=v;}float[]toArray(){return Arrays.copyOf(data,size);}}
    private static final class IntCollector{private int[]data;private int size;IntCollector(int cap){data=new int[Math.max(32,cap)];}void add(int v){if(size==data.length)data=Arrays.copyOf(data,data.length*2);data[size++]=v;}int[]toArray(){return Arrays.copyOf(data,size);}}
}
