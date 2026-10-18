package com.example.blenderpicobridge.p7f82b4c1

import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.blenderpicobridge.p7f82b4c1.data.repository.AlphaAtlasDrawer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.EglBase
import org.webrtc.GlUtil
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class AlphaAtlasDrawerTest {
    @Test fun unpackPreservesCoverageColourAndOrientation() {
        val egl=EglBase.create(null,EglBase.CONFIG_RGBA)
        egl.createPbufferSurface(8,8);egl.makeCurrent()
        val drawer=AlphaAtlasDrawer().apply { alphaAtlas=true }
        val texture=GlUtil.generateTexture(GLES20.GL_TEXTURE_2D)
        try {
            // Source rows are top-down, as decoded I420. Top image red, bottom blue.
            val atlas=ByteBuffer.allocateDirect(8*16*4)
            repeat(16) { y -> repeat(8) { x ->
                val a=if(x<4) 0 else 255
                val rgb=if(y<8) (if(y<4) intArrayOf(255,0,0) else intArrayOf(0,0,255)) else intArrayOf(a,a,a)
                atlas.put(rgb[0].toByte()).put(rgb[1].toByte()).put(rgb[2].toByte()).put(255.toByte())
            } };atlas.flip()
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,8,16,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,atlas)
            val flip=floatArrayOf(1f,0f,0f,0f,0f,-1f,0f,0f,0f,0f,1f,0f,0f,1f,0f,1f)
            drawer.drawRgb(texture,flip,8,16,0,0,8,8)
            val output=ByteBuffer.allocateDirect(8*8*4)
            GLES20.glReadPixels(0,0,8,8,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,output)
            fun value(x: Int,y: Int,c: Int)=output.get((y*8+x)*4+c).toInt() and 255
            assertTrue("Outside coverage must be transparent",value(1,1,3)<3)
            assertTrue("Inside coverage must be opaque",value(6,1,3)>252)
            assertTrue("Bottom image must stay blue",value(6,1,2)>245 && value(6,1,0)<10)
            assertTrue("Top image must stay red",value(6,6,0)>245 && value(6,6,2)<10)
        } finally { drawer.release();GLES20.glDeleteTextures(1,intArrayOf(texture),0);egl.release() }
    }
}
