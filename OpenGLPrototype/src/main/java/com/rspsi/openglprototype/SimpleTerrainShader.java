package com.rspsi.openglprototype;

import com.jogamp.opengl.GL3;
import org.joml.Matrix4f;

/** Terrain/model shader with cache texture-array sampling and colour fallback. */
public final class SimpleTerrainShader {
    private static final String VERTEX =
            "#version 330 core\n" +
            "layout(location=0) in vec3 aPosition; layout(location=1) in vec3 aColour; layout(location=2) in vec2 aTexCoord; layout(location=3) in float aTextureId;\n" +
            "uniform mat4 uViewProjection; out vec3 vColour; out vec2 vTexCoord; flat out int vTextureId;\n" +
            "void main(){vColour=aColour;vTexCoord=aTexCoord;vTextureId=(aTextureId<0.0)?-1:int(aTextureId+0.5);gl_Position=uViewProjection*vec4(aPosition,1.0);}\n";
    private static final String FRAGMENT =
            "#version 330 core\n" +
            "in vec3 vColour; in vec2 vTexCoord; flat in int vTextureId; uniform sampler2DArray uTextures; uniform int uTextureCount; out vec4 fragColor;\n" +
            "void main(){if(vTextureId>=0&&vTextureId<uTextureCount){vec4 tex=texture(uTextures,vec3(fract(vTexCoord),float(vTextureId)));if(tex.a<0.50)discard;float light=clamp(0.58+dot(vColour,vec3(0.2126,0.7152,0.0722))*0.55,0.55,1.12);fragColor=vec4(tex.rgb*light,1.0);}else fragColor=vec4(vColour,1.0);}\n";

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
