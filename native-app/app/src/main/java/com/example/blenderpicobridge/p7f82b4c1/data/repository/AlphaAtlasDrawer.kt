package com.example.blenderpicobridge.p7f82b4c1.data.repository

import android.opengl.GLES11Ext
import android.opengl.GLES20
import org.webrtc.GlRectDrawer
import org.webrtc.GlShader
import org.webrtc.GlUtil
import org.webrtc.RendererCommon

/** One decoded frame carries colour above coverage. Unpack before the decoder's UV transform. */
internal class AlphaAtlasDrawer : RendererCommon.GlDrawer {
    @Volatile var alphaAtlas=false
    private val opaque=GlRectDrawer()
    private val programs=mutableMapOf<Int,GlShader>()
    private val positions=GlUtil.createFloatBuffer(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f))
    private val coords=GlUtil.createFloatBuffer(floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f))
    private val vertex="""
        attribute vec4 in_pos;
        attribute vec2 in_tc;
        varying vec2 tc;
        void main() { gl_Position=in_pos; tc=in_tc; }
    """.trimIndent()
    private fun program(kind: Int): GlShader = programs.getOrPut(kind) {
        val extension=if(kind==0) "#extension GL_OES_EGL_image_external : require\n" else ""
        val sample=if(kind==2) """
            uniform sampler2D plane0; uniform sampler2D plane1; uniform sampler2D plane2;
            vec3 pixel(vec2 p) {
                float y=texture2D(plane0,p).r*1.16438;
                float u=texture2D(plane1,p).r; float v=texture2D(plane2,p).r;
                return vec3(y+1.59603*v-0.874202,y-0.391762*u-0.812968*v+0.531668,y+2.01723*u-1.08563);
            }
        """ else "uniform ${if(kind==0) "samplerExternalOES" else "sampler2D"} plane0; vec3 pixel(vec2 p) { return texture2D(plane0,p).rgb; }"
        GlShader(vertex,extension+"precision highp float; varying vec2 tc; uniform mat4 tex_mat;\n"+sample+"""
            void main() {
                vec2 colour=(tex_mat*vec4(tc.x,0.5+0.5*tc.y,0.0,1.0)).xy;
                vec2 coverage=(tex_mat*vec4(tc.x,0.5*tc.y,0.0,1.0)).xy;
                float a=clamp(pixel(coverage).r,0.0,1.0);
                if(a<0.008) a=0.0;
                if(a>0.992) a=1.0;
                gl_FragColor=vec4(a==0.0 ? vec3(0.0) : clamp(pixel(colour),0.0,1.0),a);
            }
        """)
    }
    private fun draw(kind: Int,textures: IntArray,matrix: FloatArray,x: Int,y: Int,w: Int,h: Int) {
        val shader=program(kind);shader.useProgram()
        shader.setVertexAttribArray("in_pos",2,positions)
        shader.setVertexAttribArray("in_tc",2,coords)
        GLES20.glUniformMatrix4fv(shader.getUniformLocation("tex_mat"),1,false,matrix,0)
        val target=if(kind==0) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        textures.forEachIndexed { index,id ->
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0+index);GLES20.glBindTexture(target,id)
            GLES20.glUniform1i(shader.getUniformLocation("plane$index"),index)
        }
        GLES20.glViewport(x,y,w,h);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        textures.indices.forEach { GLES20.glActiveTexture(GLES20.GL_TEXTURE0+it);GLES20.glBindTexture(target,0) }
        GlUtil.checkNoGLES2Error("Alpha atlas")
    }
    override fun drawOes(id: Int,m: FloatArray,fw: Int,fh: Int,x: Int,y: Int,w: Int,h: Int) {
        if(alphaAtlas) draw(0,intArrayOf(id),m,x,y,w,h) else opaque.drawOes(id,m,fw,fh,x,y,w,h)
    }
    override fun drawRgb(id: Int,m: FloatArray,fw: Int,fh: Int,x: Int,y: Int,w: Int,h: Int) {
        if(alphaAtlas) draw(1,intArrayOf(id),m,x,y,w,h) else opaque.drawRgb(id,m,fw,fh,x,y,w,h)
    }
    override fun drawYuv(ids: IntArray,m: FloatArray,fw: Int,fh: Int,x: Int,y: Int,w: Int,h: Int) {
        if(alphaAtlas) draw(2,ids,m,x,y,w,h) else opaque.drawYuv(ids,m,fw,fh,x,y,w,h)
    }
    override fun release() { opaque.release();programs.values.forEach { it.release() };programs.clear() }
}
