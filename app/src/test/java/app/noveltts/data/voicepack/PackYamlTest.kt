package app.noveltts.data.voicepack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原版语音包 YAML 的解析测试。
 *
 * fixture 的结构与真实包一致：engines.yaml 的根节点带 `!!...EngineConfig` 标签，
 * config.yaml 的每个音色项带 `!!...Speaker` 标签，字段里可能出现 `null`、emoji 与缺失项。
 */
class PackYamlTest {

    private val enginesYaml = """
        !!org.nobody.multitts.tts.engine.EngineConfig
        engines:
        - code: edgetts
          name: EdgeTTS
          note: null
          type: inner
        - code: microsoft
          name: 微软离线
          note: 需要许可证
          type: inner
    """.trimIndent()

    private val configYaml = """
        edgetts:
        - !!org.nobody.multitts.tts.speaker.Speaker
          avatar: null
          code: zh-CN-YunxiNeural
          desc: 小说、活泼、阳光
          extendUI: null
          gender: 1
          locale: zh-CN
          name: 男青年-云希🦄
          note: null
          param: zh-CN-YunxiNeural
          pitch: 1.1
          sampleRate: 24000
          speed: 1.2
          type: 1
          volume: 0.9
        - !!org.nobody.multitts.tts.speaker.Speaker
          code: en-US-AriaNeural
          gender: 2
          name: Aria
          param: en-US-AriaNeural
          type: 1
        microsoft:
        - !!org.nobody.multitts.tts.speaker.Speaker
          code: zh-CN-XiaoxiaoNeural
          gender: 2
          locale: zh-CN
          name: 晓晓
          param: zh-CN-XiaoxiaoNeural
          type: 0
        custom:
        - code: some-voice
          name: 自定义
    """.trimIndent()

    private fun parse() = PackYaml.parse(configYaml, enginesYaml)

    @Test
    fun `解析引擎元数据并标记是否受支持`() {
        val pack = parse()
        assertEquals(listOf("edgetts", "microsoft", "custom"), pack.engines.map { it.code })
        assertEquals("EdgeTTS", pack.engines[0].name)
        assertEquals("inner", pack.engines[0].type)
        assertEquals("需要许可证", pack.engines[1].note)
        assertTrue(pack.engines[0].supported)
        assertTrue(pack.engines[1].supported)
        assertFalse(pack.engines[2].supported)
    }

    @Test
    fun `解析音色字段并保留 emoji 与显式参数`() {
        val pack = parse()
        assertEquals(4, pack.voices.size)

        val yunxi = pack.voices.first { it.code == "zh-CN-YunxiNeural" }
        assertEquals("edgetts", yunxi.engineCode)
        assertEquals("男青年-云希🦄", yunxi.name)
        assertEquals("zh-CN", yunxi.locale)
        assertEquals(1, yunxi.gender)
        assertEquals(24_000, yunxi.sampleRate)
        assertEquals(1.2f, yunxi.speed, 0.0001f)
        assertEquals(1.1f, yunxi.pitch, 0.0001f)
        assertEquals(0.9f, yunxi.volume, 0.0001f)
        assertEquals("小说、活泼、阳光", yunxi.description)
        assertEquals("", yunxi.note)
        assertEquals("", yunxi.avatar)
    }

    @Test
    fun `locale 缺失时从音色名推断`() {
        val aria = parse().voices.first { it.code == "en-US-AriaNeural" }
        assertEquals("en-US", aria.locale)
        // 无 locale 也无 locale 前缀时回退 zh-CN
        val custom = parse().voices.first { it.code == "some-voice" }
        assertEquals("zh-CN", custom.locale)
    }

    @Test
    fun `在线标记来自 type 或引擎代码`() {
        val pack = parse()
        assertTrue(pack.voices.first { it.code == "zh-CN-YunxiNeural" }.isOnline)
        // microsoft 的 type: 0 表示离线
        assertFalse(pack.voices.first { it.code == "zh-CN-XiaoxiaoNeural" }.isOnline)
        // edgetts 引擎下的音色一律视为在线
        assertTrue(pack.voices.first { it.code == "en-US-AriaNeural" }.isOnline)
    }

    @Test
    fun `packId 只由两个 YAML 决定`() {
        val config = "edgetts:\n- code: a\n".toByteArray()
        val engines = "engines:\n- code: edgetts\n".toByteArray()
        val id = OriginalPackParser.packId(config, engines)

        assertTrue(id.startsWith("vp-"))
        assertEquals(19, id.length) // "vp-" + 16 hex
        assertEquals(id, OriginalPackParser.packId(config.copyOf(), engines.copyOf()))
        assertNotEquals(id, OriginalPackParser.packId(config, engines.copyOf(engines.size + 1)))
    }

    @Test
    fun `剥离类型标签但不破坏内容`() {
        val stripped = PackYaml.stripTags(enginesYaml)
        assertFalse(stripped.contains("!!"))
        assertTrue(stripped.contains("engines:"))
        assertTrue(stripped.contains("code: edgetts"))
    }
}
