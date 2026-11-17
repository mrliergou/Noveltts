package app.noveltts.ui.workbench

import kotlin.random.Random

/**
 * 工作台「填入示例」用的短篇名著 / 文学片段。
 *
 * 每条都带中文引号对话，方便演示角色匹配；长度控制在 60–140 字，避免一次合成太久。
 * 只取公版作品（作者去世已逾 50 年）的短句，并保持片段简短可读。
 */
internal object SampleTexts {

    /** 候选片段；实际取用见 [random]。 */
    val items: List<String> = listOf(
        "宝玉笑道：“这个妹妹我曾见过的。”贾母笑道：“可又是胡说，你何曾见过他？”宝玉道：“虽没见过，看着面善，心里倒像是远别重逢。”",
        "行者道：“师父放心，老孙自有主张。”三藏道：“你又有甚么主张？”行者笑道：“这妖怪既认不得我，待我变个模样，探他个虚实。”",
        "孔明曰：“主公勿忧。亮观天象，今夜必有东南风起。”玄德曰：“若得如此，破曹必矣。”",
        "孔乙己便涨红了脸，额上的青筋条条绽出，争辩道：“窃书不能算偷……窃书！……读书人的事，能算偷么？”",
        "祥子把车放下，抹了把汗，低声道：“拉车这行当，凭的是力气。”虎妞撇嘴道：“力气？力气能当饭吃？”",
    )

    /**
     * 随机取一条示例。
     *
     * [exclude] 命中时避开它，连续点「填入示例」才不至于两次完全一样；
     * 命中后候选为空（例如只剩一条）时退回全部候选，保证总有结果。
     */
    fun random(exclude: String? = null, random: Random = Random.Default): String {
        if (items.isEmpty()) return ""
        if (items.size == 1) return items.first()
        val current = exclude?.trim()
        val candidates = if (current.isNullOrEmpty()) items else items.filter { it != current }
        return (if (candidates.isEmpty()) items else candidates).random(random)
    }
}
