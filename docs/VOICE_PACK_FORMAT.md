# 语音包格式

语音包是 NovelTTS 的音色来源。应用不内置音色，也不含模型；语音包只**声明**音色，真正合成的是应用内的后端。

## 支持的后端

| `engineCode` | 后端 | 能否合成 |
| --- | --- | --- |
| `edgetts` | Edge Read Aloud（在线） | 可以，需要联网 |
| `microsoft` | 微软 Embedded Speech（离线） | 可以，需要包内带模型目录，且用户已配置合法许可证 |
| `bdotts`、`xfpeiyin`、`bdetts` 等 | — | 不可以。语音包能安装、能在包详情里看到，但音色不会注册，也不会提供给系统 |

## 原版语音包（推荐）

NovelTTS 直接读取原版 MultiTTS 的语音包。ZIP 根目录的结构：

```
pack.zip
├── config.yaml      # 引擎代码 → 音色列表
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

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `code` | 否 | 引擎内的音色标识；缺省取 `param`，再缺省为 `voice-<序号>` |
| `param` | 否 | 传给引擎的参数；Edge 下就是音色短名 |
| `name` | 否 | 显示名，缺省用 `code` |
| `locale` | 否 | 缺省按 `code` 推断，再缺省 `zh-CN` |
| `gender` | 否 | `1` 男，`2` 女，其他不标注 |
| `type` | 否 | `1` 视为在线；`edgetts` 下的音色一律视为在线 |
| `speed` / `pitch` / `volume` | 否 | 该音色的默认倍率，缺省 `1.0` |
| `desc` / `note` / `avatar` | 否 | 描述、备注、头像，仅用于展示 |

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

`type` 目前只用于展示。原版的 `url/header/param/extendUI` 等引擎扩展不构成本项目的通用接口或脚本能力；不能因为保留某个清单字段就宣称对应后端可用。

原版 YAML 里带有 `!!org.nobody.multitts...` 这类 Java 类型标签。解析前会先去掉标签，再用 SnakeYAML 的 `SafeConstructor` 读成普通的 map 和 list，不会加载任何外部类。

### 包 ID 与音色名

```
packId   = "vp-" + sha256(config.yaml + 0x00 + engines.yaml) 的前 16 位
音色系统名 = "multitts:" + packId + ":" + engineCode + ":" + voiceCode
```

`packId` 只由两个 YAML 的内容决定：同一个包重复导入得到相同的 ID，确认后覆盖升级；其他应用里保存的音色选择不会因为重装而失效。

安装时还会计算整个 ZIP 的 SHA-256 作为内容版本，参与音频缓存键。YAML 不变而模型更新时，音色名不变，旧缓存会失效。直接改动已安装的模型目录不会触发新的内容版本，更新请走重新导入。

应用已改名为 NovelTTS，音色名前缀仍是 `multitts:`。这是有意保留的：这个字符串保存在第三方阅读器和系统设置里，改掉会让所有已选音色失效。

## 旧版语音包（兼容）

旧版 `pack.json` 只是「音色声明加系统引擎绑定」，不包含 NovelTTS 的合成音源：

```json
{
  "id": "legacy-example",
  "name": "旧格式示例",
  "version": "1.0.0",
  "author": "Example",
  "description": "旧版语音包格式示例",
  "voices": [
    {
      "id": "external-default",
      "name": "外部系统音色",
      "locale": "zh-CN",
      "engine": "com.example.tts",
      "voiceName": null,
      "requiresNetwork": false
    }
  ]
}
```

- `id` 必须匹配 `^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$`。
- 这类包可以安装和展示，但**不会**注册系统音色。
- 新的语音包请使用上面的 YAML 格式。

## 在线目录

「语音包」页可以填写目录地址并刷新。目录是 JSON，两种写法都支持：

```json
{ "packs": [ { "id": "demo", "name": "示例", "url": "https://example.com/demo.zip" } ] }
```

或者直接是数组 `[ { ... } ]`。

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 包标识；不合法的条目会被忽略 |
| `url` | 是 | ZIP 直链，仅支持 HTTP(S) |
| `name` / `version` / `author` / `description` | 否 | 展示用 |
| `sha256` | 否 | 提供时校验下载内容，不匹配则拒绝安装 |
| `sizeBytes` | 否 | 服务器不返回 `Content-Length` 时用于显示进度 |
| `voiceCount` | 否 | 列表展示用 |

目录每次刷新都全量拉取，没有增量缓存。

## 安装流程与限制

写入临时目录 → 校验空间与哈希 → 解压 → 读取清单并解析 → 已安装同 ID 的包时显示新旧版本和受影响的角色 / 默认音色引用，等待确认 → 备份旧目录 → 替换。任何一步失败都会回滚，已安装的版本不受影响；取消或超时同样保留旧包。

| 项目 | 上限 |
| --- | --- |
| ZIP 大小（导入或下载） | 1 GB |
| 解压后总大小 | 1.5 GB |
| 解压条目数 | 5000 |
| 单份 YAML / JSON 清单、在线目录 | 4 MB |
| JSON 嵌套深度 | 16 层 |
| 可用空间 | 不少于包体的 3 倍 |

其他约束：

- 拒绝含 `..`、绝对路径或解压后越界的条目。`__MACOSX` 和 `.DS_Store` 不落盘，但仍计入解压上限。
- 微软音色的模型目录必须位于包自身的 `microsoft` 目录内，越界的路径视为不可用。
- JSON 清单不接受单引号、注释等非标准写法。
- JSON 文件可带一个起始 BOM，不能在内容中夹入或重复 BOM。YAML 根节点必须是映射，同一引擎内不能出现重复音色 code。
- 下载的大小在读取过程中逐步检查，不依赖服务器给出的 `Content-Length`。

安装提交、删除与扫描共用锁。启动时只清理工具生成的临时目录；目标包缺失且只有一份可解析备份时恢复，有正常目标时清理旧备份，损坏或多份备份保留并提示检查。回滚失败会明确报错，不能保证在文件系统损坏时自动恢复。

## 通过外部链接安装

其他应用或 adb 可以请求安装语音包或设置在线目录：

```powershell
# 请求安装 ZIP 直链
adb shell am start -n app.noveltts/.MainActivity --es packUrl "https://example.com/pack.zip"

# 请求设置在线目录并刷新
adb shell am start -n app.noveltts/.MainActivity --es catalog "https://example.com/catalog.json"

# 直接打开语音包页
adb shell am start -n app.noveltts/.MainActivity --es open voicepacks
```

安装和目录请求都会先在应用内弹出确认框，用户确认后才下载或修改设置；取消则什么都不做。

## 已知限制

- 没有发布者签名校验。`sha256` 只能发现传输损坏和简单篡改，不能证明来源，请只安装可信来源的语音包。
- 应用允许明文 HTTP，以便使用局域网或自建服务；公网分发请使用 HTTPS 并提供 `sha256`。
- 微软离线包必须随包提供模型目录，许可证由用户自行填写。缺许可证的音色在应用内标为「缺许可证」，不会出现在系统音色列表里。
- 删除语音包后，引用它的角色和阅读方案不会被自动删除；应用会提示音色不可用并回落，重新安装同 ID 的包即可恢复。
