# 语音包

语音包是复刻版的音色来源。应用本身不内置任何音色，也没有模型权重——它只**声明**音色，
真正出声的是两种后端：

| `engineCode` | 后端 | 说明 |
| --- | --- | --- |
| `edgetts` | `EdgeTtsEngine`（在线） | Edge Read Aloud WebSocket 接口；需要联网 |
| `microsoft` | `MicrosoftEmbeddedEngine`（离线） | 微软嵌入式语音；需要**用户自己**提供合法的 Embedded Speech 许可证与本地模型目录 |

其他 `engineCode` 的语音包仍能安装、能在界面里看到，但音色不会注册给系统，合成时返回 `BAD_REQUEST`。

## 1. 原版语音包（推荐）

复刻版直接吃原版 MultiTTS 的语音包 zip。zip 根目录必须是：

```
pack.zip
├── config.yaml      # 引擎代码 -> 音色列表
├── engines.yaml     # 引擎元数据
└── （可选）模型目录  # microsoft 离线引擎需要
```

### config.yaml

按引擎代码分组，每个引擎下是音色数组：

```yaml
edgetts:
  - code: zh-CN-XiaoxiaoNeural
    name: 晓晓
    locale: zh-CN
    gender: 2
    type: 1
    speed: 1.0
    pitch: 1.0
    volume: 1.0
    desc: 普通话女声
microsoft:
  - code: zh-CN-YunxiNeural
    name: 云希
    locale: zh-CN
    type: 0
```

字段说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `code` | 否 | 引擎内的语音标识；缺省回退到 `param`，再缺省为 `voice-<序号>` |
| `param` | 否 | 传给引擎的参数名；Edge 里就是 voice short name |
| `name` | 否 | 界面显示名，缺省用 `code` |
| `locale` | 否 | 缺省按 `code` 推断，再缺省 `zh-CN` |
| `gender` | 否 | `1` 男 / `2` 女 / 其他不标 |
| `type` | 否 | `1` 视为在线；`edgetts` 引擎下的音色一律视为在线 |
| `speed` / `pitch` / `volume` | 否 | 该音色的默认倍率，缺省 `1.0` |
| `desc` / `note` / `avatar` | 否 | 描述、备注、头像（仅展示） |

### engines.yaml

```yaml
engines:
  - code: edgetts
    name: Edge 在线语音
    type: inner
    note: 非官方接口
  - code: microsoft
    name: 微软离线语音
    type: inner
    note: 需要许可证
```

`type` 目前只用于展示。原版的 YAML 里带 `!!org.nobody.multitts.tts.speaker.Speaker` 这类 Java 类型标签，
解析前会被剥离，再用 snakeyaml 的 `SafeConstructor` 读成普通 map/list——**不会**把原版 APK 的类引入本项目。

### 包 ID 与音色名

```
packId = "vp-" + sha256(config.yaml + 0x00 + engines.yaml).take(16)
音色系统名 = "multitts:" + packId + ":" + engineCode + ":" + voiceCode
```

`packId` 只由两个 YAML 决定，所以同一个包重复导入得到同一 ID，可直接覆盖升级；
音色系统名稳定，其他应用里保存的音色选择不会因为重装而失效。

> ### 前缀 `multitts:` 为什么没跟着改名（重要）
>
> 应用名已改为 **NovelTTS**，但音色前缀仍是 `multitts:`，这是**有意为之**，不是遗漏。
>
> 这个字符串同时被三处记住：第三方阅读器/系统设置里保存的音色选择、本应用 `SettingsStore`
> 里的 `narratorVoiceId`/`dialogueVoiceId`/`systemVoiceId`、角色规则里的 `voiceId`。
> 一旦换前缀，所有已选音色全部失效、退回默认音色，而外部应用保存的旧名字我们无法回写。
>
> **计划**：等功能稳定后再整体迁移。届时必须一次改完这些点：
>
> 1. `VoiceRegistry.systemName()` 的生成逻辑（改为新前缀）；
> 2. `VoiceRegistry.find()` 增加旧 `multitts:` ID 的兼容映射（读旧写新），否则老用户音色瞬间全丢；
> 3. `SettingsStore`、`RulesRepository` 里持久化的 voiceId 做一次性迁移；
> 4. 界面里展示「系统音色名」的两处说明文案；
> 5. 发布说明里告知用户：外部阅读器需要重新选一次音色（这部分无法自动迁移）。

## 2. 旧版语音包（兼容）

旧版 `pack.json` 只是「音色声明 + 系统引擎绑定」，用于旧版本的历史数据：

```json
{
  "id": "demo-xiaomi",
  "name": "示例语音包",
  "version": "1.0.0",
  "author": "MultiTTS",
  "description": "绑定已装系统引擎的示例语音包",
  "voices": [
    {
      "id": "mibrain-default",
      "name": "小米系统音色",
      "locale": "zh-CN",
      "engine": "com.xiaomi.mibrain.speech",
      "voiceName": null,
      "requiresNetwork": false
    }
  ]
}
```

- `id` 必须匹配 `^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$`；
- 这类包可以安装、可以展示，但**不会**注册 `multitts:*` 音色给系统（它绑定的是别的引擎的语音）；
- 新项目请优先用原版 YAML 格式。

`samples/demo-xiaomi/pack.json` 是一个可直接导入的示例，`scripts/make-pack.ps1` 能把它打成 zip。

## 3. 在线目录

「语音包」页可以填写目录地址并刷新。目录是 JSON，两种写法都支持：

```json
{ "packs": [ { "id": "demo", "name": "示例", "url": "https://example.com/demo.zip" } ] }
```

或直接是数组 `[ { ... } ]`。字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 包标识；不合法时该条目被忽略 |
| `url` | 是 | zip 直链 |
| `name` / `version` / `author` / `description` | 否 | 展示用 |
| `sha256` | 否 | 提供则校验下载内容，不匹配拒绝安装 |
| `sizeBytes` | 否 | 进度条用；服务器没给 `Content-Length` 时也能显示总量 |
| `voiceCount` | 否 | 列表展示用 |

下载走 `HttpURLConnection`（连接 15 s / 读 20 s，UA `MultiTTS-Replica`），非 2xx 直接报错。

## 4. 安装与安全边界

安装流程：写入临时目录 → 大小/空间校验 → 解压 → 校验 `pack.json` 或两个 YAML → 解析 → 旧目录备份 → `renameTo` 替换 → 失败回滚。

- **Zip Slip 防护**：拒绝 `..`、绝对路径、解压后越界的条目，跳过 `__MACOSX`；
- **上限**：zip 体积上限与可用空间检查（要求可用空间 ≥ 3 倍包体）；
- **原子性**：解析失败不会破坏已安装版本；
- **许可证**：离线音色缺许可证时 `usable=false`，界面标「缺许可证」，且**不会**出现在系统 `onGetVoices()` 里——
  避免其他应用选中一个必然失败的音色。

## 5. adb 自动化入口

```powershell
# 直接安装 zip 直链
adb shell am start --es packUrl "http://127.0.0.1:8765/pack.zip" -n app.noveltts/.MainActivity

# 设置在线目录并刷新
adb shell am start --es catalog "http://127.0.0.1:8765/catalog.json" -n app.noveltts/.MainActivity

# 直接打开语音包页
adb shell am start --es open voicepacks -n app.noveltts/.MainActivity
```

期望日志：

```
I VoicePackCatalog: downloaded <id> (<n>B)
I VoicePackStore: installed voice pack <id> v<version> (<n> voices)
I MainViewModel: packs loaded: <n> (<id>@<version>)
```

局域网/自建服务常用明文 HTTP，`res/xml/network_security_config.xml` 显式允许 cleartext；
公网分发请用 HTTPS 并提供 `sha256`。

## 6. 已知限制

- 没有包签名与发布者校验，`sha256` 只防传输损坏与简单篡改；
- 目录不做增量缓存，每次刷新都全量拉取；
- `microsoft` 离线包的模型目录需随包提供，且许可证必须由用户自己填（不随仓库分发）；
- 旧版 `pack.json` 不会注册系统音色（见 §2）。
