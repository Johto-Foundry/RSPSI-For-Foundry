package com.rspsi.openglprototype;

import com.jogamp.opengl.GL3;
import org.joml.Matrix4f;

/** Terrain/model shader with cache texture-array sampling and colour fallback. */
public final class SimpleTerrainShader {
    private static final String VERTEX =
            "#version 330 core\n" +
            "layout(location=0) in vec3 aPosition; layout(location=1) in vec3 aColour; layout(location=2) in vec2 aTexCoord; layout(location=3) in float aTextureId;\n" +
            "uniform mat4 uViewProjection; out vec3 vColour; out vec2 vTexCoord; flat out int vTextureId; flat out int vModelTexture;\n" +
            "void main(){vColour=aColour;vTexCoord=aTexCoord;if(aTextureId<0.0){vTextureId=-1;vModelTexture=0;}else if(aTextureId>=1000.0){vTextureId=int((aTextureId-1000.0)+0.5);vModelTexture=1;}else{vTextureId=int(aTextureId+0.5);vModelTexture=0;}gl_Position=uViewProjection*vec4(aPosition,1.0);}\n";
    private static final String FRAGMENT =
            "#version 330 core\n" +
            "in vec3 vColour; in vec2 vTexCoord; flat in int vTextureId; flat in int vModelTexture; uniform sampler2DArray uTextures; uniform int uTextureCount; out vec4 fragColor;\n" +
            // Terrain kept on the existing wrap path until terrain parity is tackled separately.
            "ivec2 rsWrappedTexelCoord(vec2 uv){vec2 wrapped=fract(uv);ivec2 texel=ivec2(floor(wrapped*128.0));return clamp(texel,ivec2(0),ivec2(127));}\n" +
            // GameRasterizer.drawTexturedLine does NOT wrap model U. It clamps the
            // horizontal accumulator to 0..16256 (0..127 texels) while V is masked
            // with 0x3f80, i.e. wrapped modulo 128 rows.
            "ivec2 rsModelTexelCoord(vec2 uv){int x=int(floor(uv.x*128.0));x=clamp(x,0,127);float wy=fract(uv.y);int y=int(floor(wy*128.0));y=clamp(y,0,127);return ivec2(x,y);}\n" +
            "float exactCutoutAlpha(int layer, vec2 uv){return texelFetch(uTextures,ivec3(rsWrappedTexelCoord(uv),layer),0).a;}\n" +
            "vec4 exactModelTexel(int layer, vec2 uv){return texelFetch(uTextures,ivec3(rsModelTexelCoord(uv),layer),0);}\n" +
            // TextureLoaderOSRS builds four shade banks: 1, 7/8, 3/4 and 5/8.
            // drawTexturedLine additionally shifts the packed texel by shade>>6,
            // which is effectively another 1/2 step because loader RGB is masked.
            "float rsModelShadeFactor(float encoded){int s=int(clamp(encoded,0.0,1.0)*127.0+0.5);int bank=(s>>4)&3;float f=bank==0?1.0:(bank==1?0.875:(bank==2?0.75:0.625));if((s>>6)!=0)f*=0.5;return f;}\n" +
            "void main(){if(vTextureId>=0&&vTextureId<uTextureCount){if(vModelTexture==1){vec4 tex=exactModelTexel(vTextureId,vTexCoord);if(tex.a<0.50)discard;float factor=rsModelShadeFactor(vColour.r);fragColor=vec4(tex.rgb*factor,1.0);}else{if(exactCutoutAlpha(vTextureId,vTexCoord)<0.50)discard;vec4 tex=texture(uTextures,vec3(fract(vTexCoord),float(vTextureId)));float light=clamp(0.58+dot(vColour,vec3(0.2126,0.7152,0.0722))*0.55,0.55,1.12);fragColor=vec4(tex.rgb*light,1.0);}}else fragColor=vec4(vColour,1.0);}\n";

    private final TerrainTextureArray textures=new TerrainTextureArray();
    private int program,viewProjectionLocation=-1,textureCountLocation=-1;

    public void init(GL3 gl){
        int vertex=compile(gl,GL3.GL_VERTEX_SHADER,VERTEX),fragment=compile(gl,GL3.GL_FRAGMENT_SHADER,FRAGMENT);
        program=gl.glCreateProgram();gl.glAttachShader(program,vertex);gl.glAttachShader(program,fragment);gl.glLinkProgram(program);
        int[] ok=new int[1];gl.glGetProgramiv(program,GL3.GL_LINK_STATUS,ok,0);if(ok[0]==0){int[] len=new int[1];gl.glGetProgramiv(program,GL3.GL_INFO_LOG_LENGTH,len,0);byte[] log=new byte[Math.max(len[0],1)];gl.glGetProgramInfoLog(program,log.length,null,0,log,0);throw new IllegalStateException("OpenGL program link failed: "+new String(log));}
        viewProjectionLocation=gl.glGetUniformLocation(program,"uViewProjection");textureCountLocation=gl.glGetUniformLocation(program,"uTextureCount");
        gl.glUseProgram(program);int sampler=gl.glGetUniformLocation(program,"uTextures");if(sampler>=0)gl.glUniform1i(sampler,0);
        gl.glDetachShader(program,vertex);gl.glDetachShader(program,fragment);gl.glDeleteShader(vertex);gl.glDeleteShader(fragment);
        textures.upload(gl);
    }
    private int compile(GL3 gl,int type,String source){int shader=gl.glCreateShader(type);String[] src={source};int[] lengths={source.length()};gl.glShaderSource(shader,1,src,lengths,0);gl.glCompileShader(shader);int[] ok=new int[1];gl.glGetShaderiv(shader,GL3.GL_COMPILE_STATUS,ok,0);if(ok[0]==0){int[] len=new int[1];gl.glGetShaderiv(shader,GL3.GL_INFO_LOG_LENGTH,len,0);byte[] log=new byte[Math.max(len[0],1)];gl.glGetShaderInfoLog(shader,log.length,null,0,log,0);throw new IllegalStateException("OpenGL shader compile failed: "+new String(log));}return shader;}
    public void use(GL3 gl){gl.glUseProgram(program);textures.bind(gl);if(textureCountLocation>=0)gl.glUniform1i(textureCountLocation,textures.getLayerCount());}
    public void setViewProjection(GL3 gl,Matrix4f matrix){if(viewProjectionLocation<0)return;float[] values=new float[16];matrix.get(values);gl.glUniformMatrix4fv(viewProjectionLocation,1,false,values,0);}
    public void dispose(GL3 gl){textures.dispose(gl);if(program!=0){gl.glDeleteProgram(program);program=0;viewProjectionLocation=-1;textureCountLocation=-1;}}
}
