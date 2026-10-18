package com.example.blenderpicobridge.p7f82b4c1.domain.usecase
import org.junit.Assert.*
import org.junit.Test
class PalmToolsUseCaseTest {
    private val eye=listOf(0f,0f,0f)
    private val forward=listOf(0f,0f,-1f)
    private val left=PalmToolsHand(false,listOf(0f,0f,-.5f),listOf(0f,-.05f,-.5f),
        listOf(.03f,0f,-.5f),listOf(-.03f,0f,-.5f),listOf(.03f,.10f,-.5f),listOf(-.03f,.08f,-.5f))
    @Test fun openPalmNeedsDwellAndButtonIsOutsideLittleFinger() {
        val useCase=PalmToolsUseCase()
        assertNull(useCase.update(left,eye,forward,0));assertNull(useCase.update(left,eye,forward,449))
        val button=useCase.update(left,eye,forward,450)!!
        assertTrue(button[0]<left.littleBase[0]-.09f)
        assertTrue(button[2]>left.palm[2])
        assertNull(useCase.update(null,eye,forward,500))
        assertNull(useCase.update(left,eye,forward,501))
    }
    @Test fun backOfHandFoldedFingersAndLookingAwayNeverRecall() {
        val useCase=PalmToolsUseCase()
        for(now in listOf(0L,1000L)) {
            assertNull(useCase.update(left.copy(right=true),eye,forward,now))
            assertNull(useCase.update(left.copy(indexTip=left.indexBase),eye,forward,now))
            assertNull(useCase.update(left,eye,listOf(1f,0f,0f),now))
            assertNull(useCase.update(left,listOf(Float.NaN,0f,0f),forward,now))
        }
    }
    @Test fun movingDuringDwellAndSwitchingHandsRestartsDwell() {
        val useCase=PalmToolsUseCase()
        useCase.update(left,eye,forward,0)
        fun moved(v: List<Float>)=v.mapIndexed{i,x->if(i==0)x+.05f else x}
        val other=left.copy(palm=moved(left.palm),wrist=moved(left.wrist),indexBase=moved(left.indexBase),littleBase=moved(left.littleBase),indexTip=moved(left.indexTip),littleTip=moved(left.littleTip))
        assertNull(useCase.update(other,eye,forward,400))
        assertNull(useCase.update(other,eye,forward,600))
        assertNotNull(useCase.update(other,eye,forward,850))
    }
}
