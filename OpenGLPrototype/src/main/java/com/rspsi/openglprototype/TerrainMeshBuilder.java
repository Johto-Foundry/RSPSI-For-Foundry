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
    private static final int CHUNK_SIZE=64;
    private static final float TILE_SIZE=128.0f;
    private static final int HIDDEN_COLOUR=0xbc614e;
    private TerrainMeshBuilder(){}

    public static TerrainMeshSnapshot build(Chunk chunk,int plane){
        Client client=Client.getSingleton(); SceneGraph graph=client==null?null:client.sceneGraph;
        if(chunk==null||chunk.mapRegion==null||graph==null) return empty();
        FloatCollector pos=new FloatCollector(CHUNK_SIZE*CHUNK_SIZE*54), col=new FloatCollector(CHUNK_SIZE*CHUNK_SIZE*54), uv=new FloatCollector(CHUNK_SIZE*CHUNK_SIZE*36), tex=new FloatCollector(CHUNK_SIZE*CHUNK_SIZE*18);
        IntCollector idx=new IntCollector(CHUNK_SIZE*CHUNK_SIZE*18);
        int fallback=0, shapedCount=0, simpleCount=0, textured=0;
        for(int ly=0;ly<CHUNK_SIZE;ly++) for(int lx=0;lx<CHUNK_SIZE;lx++){
            int mx=chunk.offsetX+lx,my=chunk.offsetY+ly; SceneTile tile=null;
            if(plane>=0&&plane<graph.tiles.length&&mx>=0&&mx<graph.width&&my>=0&&my<graph.length) tile=graph.tiles[plane][mx][my];
            ShapedTile shaped=tile==null?null:tile.temporaryShapedTile.orElse(tile.shape);
            SimpleTile simple=tile==null?null:tile.temporarySimpleTile.orElse(tile.simple);
            if(shaped!=null&&shaped.getTriangleA()!=null){ textured+=appendShaped(shaped,pos,col,uv,tex,idx); shapedCount++; }
            else if(simple!=null){ if(appendSimple(chunk,plane,mx,my,simple,pos,col,uv,tex,idx)) textured+=2; simpleCount++; }
            else { appendFallback(chunk,plane,mx,my,pos,col,uv,tex,idx); fallback++; }
        }
        System.out.println("[OPENGL-TERRAIN] chunk="+(chunk.offsetX/64)+","+(chunk.offsetY/64)+" simple="+simpleCount+" shaped="+shapedCount+" fallback="+fallback+" texturedTriangles="+textured+" triangles="+(idx.size/3));
        return new TerrainMeshSnapshot(pos.toArray(),col.toArray(),uv.toArray(),tex.toArray(),idx.toArray());
    }

    private static TerrainMeshSnapshot empty(){ return new TerrainMeshSnapshot(new float[0],new float[0],new float[0],new float[0],new int[0]); }

    private static int appendShaped(ShapedTile t,FloatCollector p,FloatCollector c,FloatCollector uv,FloatCollector tex,IntCollector ind){
        int[] xs=t.getOrigVertexX(),ys=t.getOrigVertexY(),zs=t.getOrigVertexZ(),a=t.getTriangleA(),b=t.getTriangleB(),cc=t.getTriangleC(),ha=t.getTriangleHslA(),hb=t.getTriangleHslB(),hc=t.getTriangleHslC(),textures=t.getTriangleTexture(),display=t.getDisplayColor();
        if(xs==null||ys==null||zs==null||a==null||b==null||cc==null)return 0;
        int textured=0;
        for(int tri=0;tri<a.length;tri++){
            int ia=a[tri],ib=b[tri],ic=cc[tri]; if(!valid(ia,xs.length)||!valid(ib,xs.length)||!valid(ic,xs.length))continue;
            int textureId=(textures!=null&&tri<textures.length)?textures[tri]:-1; boolean hasTexture=textureId>=0; if(hasTexture)textured++;
            int fb=t.getUnderlayColour(); if(hasTexture){ if(display!=null&&tri<display.length)fb=paletteRgb(display[tri],t.getTextureColour()); else fb=paletteRgb(t.getTextureColour(),t.getUnderlayColour()); }
            int ca=ha!=null&&tri<ha.length?ha[tri]:fb, cb=hb!=null&&tri<hb.length?hb[tri]:fb, cv=hc!=null&&tri<hc.length?hc[tri]:fb;
            int base=p.size/3;
            vertex(p,c,uv,tex,xs[ia],-ys[ia],zs[ia],paletteRgb(ca,fb),textureId);
            vertex(p,c,uv,tex,xs[ib],-ys[ib],zs[ib],paletteRgb(cb,fb),textureId);
            vertex(p,c,uv,tex,xs[ic],-ys[ic],zs[ic],paletteRgb(cv,fb),textureId);
            ind.add(base);ind.add(base+1);ind.add(base+2);
        }
        return textured;
    }

    private static boolean appendSimple(Chunk chunk,int plane,int x,int y,SimpleTile t,FloatCollector p,FloatCollector c,FloatCollector uv,FloatCollector tex,IntCollector ind){
        float x0=x*TILE_SIZE,x1=(x+1)*TILE_SIZE,z0=y*TILE_SIZE,z1=(y+1)*TILE_SIZE;
        float h00=-chunk.mapRegion.tileHeights[plane][x][y],h10=-chunk.mapRegion.tileHeights[plane][x+1][y],h01=-chunk.mapRegion.tileHeights[plane][x][y+1],h11=-chunk.mapRegion.tileHeights[plane][x+1][y+1];
        int floor=resolveTileRgb(chunk,plane,x,y), textureId=t.getTexture(), fb=textureId>=0?paletteRgb(t.getColour(),floor):floor;
        int centre=paletteRgb(t.getCentreColour(),fb),east=paletteRgb(t.getEastColour(),fb),north=paletteRgb(t.getNorthColour(),fb),ne=paletteRgb(t.getNorthEastColour(),fb);
        int base=p.size/3;
        vertex(p,c,uv,tex,x0,h00,z0,centre,textureId); vertex(p,c,uv,tex,x0,h01,z1,north,textureId); vertex(p,c,uv,tex,x1,h10,z0,east,textureId);
        vertex(p,c,uv,tex,x1,h10,z0,east,textureId); vertex(p,c,uv,tex,x0,h01,z1,north,textureId); vertex(p,c,uv,tex,x1,h11,z1,ne,textureId);
        for(int i=0;i<6;i++)ind.add(base+i); return textureId>=0;
    }

    private static void appendFallback(Chunk chunk,int plane,int x,int y,FloatCollector p,FloatCollector c,FloatCollector uv,FloatCollector tex,IntCollector ind){
        float x0=x*TILE_SIZE,x1=(x+1)*TILE_SIZE,z0=y*TILE_SIZE,z1=(y+1)*TILE_SIZE;
        float h00=-chunk.mapRegion.tileHeights[plane][x][y],h10=-chunk.mapRegion.tileHeights[plane][x+1][y],h01=-chunk.mapRegion.tileHeights[plane][x][y+1],h11=-chunk.mapRegion.tileHeights[plane][x+1][y+1]; int rgb=resolveTileRgb(chunk,plane,x,y),base=p.size/3;
        vertex(p,c,uv,tex,x0,h00,z0,rgb,-1);vertex(p,c,uv,tex,x0,h01,z1,rgb,-1);vertex(p,c,uv,tex,x1,h10,z0,rgb,-1);vertex(p,c,uv,tex,x1,h10,z0,rgb,-1);vertex(p,c,uv,tex,x0,h01,z1,rgb,-1);vertex(p,c,uv,tex,x1,h11,z1,rgb,-1);
        for(int i=0;i<6;i++)ind.add(base+i);
    }

    private static void vertex(FloatCollector p,FloatCollector c,FloatCollector uv,FloatCollector tex,float x,float y,float z,int rgb,int textureId){
        p.add(x);p.add(y);p.add(z); c.add(((rgb>>16)&255)/255f);c.add(((rgb>>8)&255)/255f);c.add((rgb&255)/255f);
        // World-space UVs deliberately repeat once per 128-unit RuneScape tile.
        uv.add(x/TILE_SIZE);uv.add(z/TILE_SIZE); tex.add(textureId);
    }

    private static boolean valid(int i,int n){return i>=0&&i<n;}
    private static int paletteRgb(int hsl,int fallback){
        if(hsl==HIDDEN_COLOUR||hsl<0)return sanitise(fallback); GameRasterizer r=GameRasterizer.getInstance();
        if(r!=null&&r.colourPalette!=null&&hsl<r.colourPalette.length){int rgb=r.colourPalette[hsl];if((rgb&0xffffff)!=0)return sanitise(rgb);} return sanitise(fallback);
    }
    private static int resolveTileRgb(Chunk chunk,int plane,int x,int y){
        int ov=chunk.mapRegion.overlays[plane][x][y]&255; if(ov>0&&FloorDefinitionLoader.instance!=null){Floor f=FloorDefinitionLoader.getOverlay(ov-1);if(f!=null){int second=f.getAnotherRgb();if(second>=0&&second!=0xff00ff)return sanitise(second);return sanitise(f.getRgb());}}
        int un=chunk.mapRegion.underlays[plane][x][y]&255; if(un>0&&FloorDefinitionLoader.instance!=null){Floor f=FloorDefinitionLoader.getUnderlay(un-1);if(f!=null)return sanitise(f.getRgb());} return 0x6f8f43;
    }
    private static int sanitise(int rgb){if(rgb<0||rgb==0xff00ff||rgb==HIDDEN_COLOUR||(rgb&0xffffff)==0)return 0x6f8f43;return rgb&0xffffff;}

    private static final class FloatCollector{private float[] data;private int size;FloatCollector(int cap){data=new float[Math.max(cap,32)];}void add(float v){if(size==data.length)data=Arrays.copyOf(data,data.length*2);data[size++]=v;}float[] toArray(){return Arrays.copyOf(data,size);}}
    private static final class IntCollector{private int[] data;private int size;IntCollector(int cap){data=new int[Math.max(cap,32)];}void add(int v){if(size==data.length)data=Arrays.copyOf(data,data.length*2);data[size++]=v;}int[] toArray(){return Arrays.copyOf(data,size);}}
}
