package app.noveltts.engine

/** 合成分段进度回调：每段开始合成时触发一次，[index] 从 0 开始。 */
fun interface SegmentProgressListener {
    fun onSegmentStart(index: Int, total: Int, text: String)
}
