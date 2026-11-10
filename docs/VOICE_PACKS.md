# 语音包（Voice Pack）

语音包是一个 zip 文件，用声明的方式描述「有哪些音色、每个音色绑定到哪个引擎的哪个语音」。
应用安装后即可在「语音包」界面查看、应用或删除，也支持从在线目录直接下载安装。

> 当前语音包只做**音色声明**：它不会携带音频或模型文件。音色由已安装的系统 TTS 引擎提供，
> 应用语音包里的音色时，应用会切换到对应引擎并选中其语音名。本地模型音色会在此格式上扩展
> `model` 字段（后续阶段）。

## 1. 包结构

```
demo-xiaomi-1.0.0.zip
├── pack.json          # 必需，必须在 zip 根目录
└── README.txt         # 可选，其余文件会原样保留
```

`pack.json`：

```json
{
  "schema": 1,
  "id": "demo-xiaomi",
  "name": "示例语音包",
  "version": "1.0.0",
  "author": "MultiTTS",
  "description": "绑定已装系统引擎的示例语音包。",
  "voices": [
    {
      "id": "mibrain-default",
      "name": "小米系统音色",
      "locale": "zh-CN",
      "engine": "com.xiaomi.mibrain.speech",
      "voiceName": "xiaomi-voice-name",
      "requiresNetwork": false
    },
    {
      "id": "follow-current",
      "name": "跟随当前引擎",
      "locale": "zh-CN"
    }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 包标识，`[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}`，同时作为安装目录名 |
| `name` | 否 | 显示名，缺省用 `id` |
| `version` | 否 | 版本字符串，缺省 `1.0.0` |
| `author` / `description` | 否 | 展示用 |
| `voices[].id` | 建议 | 音色标识 |
| `voices[].name` | 否 | 音色显示名 |
| `voices[].locale` | 否 | 语言标签，缺省 `zh-CN` |
| `voices[].engine` | 否 | 目标引擎包名；省略表示沿用当前引擎 |
| `voices[].voiceName` | 否 | 目标引擎内的语音名（`TextToSpeech.getVoices()` 的名字）；省略则只切换引擎 |
| `voices[].requiresNetwork` | 否 | 该音色是否需要联网 |

安装在 `files/voicepacks/<id>/`，`pack.json` 会被补写 `installedAt` 与 `sourceUrl`。

## 2. 在线目录

目录是一个 JSON 文档，两种写法都支持：

```json
{
  "schema": 1,
  "packs": [
    {
      "id": "demo-xiaomi",
      "name": "示例语音包",
      "version": "1.0.0",
      "author": "MultiTTS",
      "description": "绑定已装系统引擎的示例语音包。",
      "url": "https://example.com/packs/demo-xiaomi-1.0.0.zip",
      "sha256": "358a7de20bd5f4fb78cbe09c5676bd0b26a05eedf0922ea0ebe9d85222e88586",
      "sizeBytes": 599,
      "voiceCount": 2
    }
  ]
}
```

或直接是数组 `[ { ... } ]`。除 `id`、`url` 外都可省略；`sha256` 存在时会严格校验下载内容，
不匹配则安装失败且不会破坏已装版本。

在应用里填「在线目录地址」→ 刷新，即可看到条目并逐个下载。

## 3. 本地导入

「语音包 → 导入本地 zip」使用系统文件选择器（SAF）读取 zip，无需存储权限。

## 4. 自动化 / 分发入口

```powershell
# 直接安装一个 zip 直链
adb shell am start --es packUrl "http://127.0.0.1:8765/demo-xiaomi-1.0.0.zip" `
  -n org.nobody.multitts.replica/.MainActivity

# 设置并刷新在线目录
adb shell am start --es catalog "http://127.0.0.1:8765/catalog.json" `
  -n org.nobody.multitts.replica/.MainActivity

# 直接打开「语音包」界面（截屏 / 演示用）
adb shell am start --es open voicepacks -n org.nobody.multitts.replica/.MainActivity
```

本机联调可用 `adb reverse tcp:8765 tcp:8765` + `python -m http.server 8765`。

## 5. 安全边界

- 允许明文 HTTP：目录与包通常自建在 NAS / 局域网，公网分发请自行使用 HTTPS 并填写 `sha256`。
- 解压带路径穿越与 `..` 校验，非法路径直接拒绝；安装采用临时目录 + 校验通过后替换，失败不影响已装版本。
- 语音包只声明音色，不会执行包内代码。

## 6. 待办

- 语音包音色进入「音色库」列表并支持按包分组（当前在语音包界面内单独列出）。
- 本地模型包：`model` 字段（模型文件、采样率、推理参数）与下载进度/断点续传。
- 包签名（Ed25519）与来源白名单，取代仅有的 sha256 校验。
