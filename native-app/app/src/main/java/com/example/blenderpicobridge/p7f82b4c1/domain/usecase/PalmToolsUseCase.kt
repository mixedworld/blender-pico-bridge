package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import kotlin.math.sqrt

data class PalmToolsHand(val right: Boolean,val palm: List<Float>,val wrist: List<Float>,
    val indexBase: List<Float>,val littleBase: List<Float>,val indexTip: List<Float>,val littleTip: List<Float>)

/** Open-palm/head-look heuristic in global metres. This is not an eye-gaze measurement. */
class PalmToolsUseCase(private val dwellMs: Long=450) {
    private var since: Long?=null
    private var hand: Boolean?=null
    private var origin: List<Float>?=null
    private fun sub(a: List<Float>,b: List<Float>)=List(3){a[it]-b[it]}
    private fun add(a: List<Float>,b: List<Float>)=List(3){a[it]+b[it]}
    private fun mul(a: List<Float>,s: Float)=a.map{it*s}
    private fun dot(a: List<Float>,b: List<Float>)=a.indices.sumOf{(a[it]*b[it]).toDouble()}.toFloat()
    private fun length(a: List<Float>)=sqrt(dot(a,a))
    private fun unit(a: List<Float>)=mul(a,1f/length(a).also{require(it>.0001f)})
    private fun cross(a: List<Float>,b: List<Float>)=listOf(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
    fun reset(){since=null;hand=null;origin=null}
    fun update(value: PalmToolsHand?,eye: List<Float>?,forward: List<Float>?,now: Long): List<Float>? {
        val candidate=runCatching {
            require(value!=null && eye!=null && forward!=null)
            require((listOf(eye,forward,value.palm,value.wrist,value.indexBase,value.littleBase,value.indexTip,value.littleTip)).all{it.size==3 && it.all(Float::isFinite)})
            val towardsEye=sub(eye,value.palm);require(length(towardsEye) in .22f..1.1f)
            val normal=unit(cross(sub(value.indexBase,value.wrist),sub(value.littleBase,value.wrist)))
            require(dot(mul(normal,if(value.right)-1f else 1f),unit(towardsEye))>.65f)
            require(dot(unit(forward),unit(sub(value.palm,eye)))>.8f)
            require(length(sub(value.indexTip,value.wrist))>length(sub(value.indexBase,value.wrist))+.035f)
            require(length(sub(value.littleTip,value.wrist))>length(sub(value.littleBase,value.wrist))+.03f)
            add(add(value.littleBase,mul(unit(sub(value.littleBase,value.indexBase)),.105f)),mul(unit(towardsEye),.035f))
        }.getOrNull()
        if(candidate==null){reset();return null}
        if(hand!=value!!.right || origin==null || (since!=null && now-since!!<dwellMs && length(sub(candidate,origin!!))>.035f)) {
            hand=value.right;since=now;origin=candidate
        }
        return candidate.takeIf{now-(since ?: now)>=dwellMs}
    }
}
