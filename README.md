# MultiTTS 复刻版

以 Kotlin + Jetpack Compose 重实现 `org.nobody.multitts` 的**系统 TTS 引擎**能力。
复刻版包名为 `org.nobody.multitts.replica`，可与原版共存安装，便于真机对照。

> 当前状态：**P0 进行中**。应用已是一个可用的"TTS 客户端 + 工作台"，但**尚未注册 `TextToSpeechService`**，因此还不是系统可选的语音引擎。

## 已有能力

- Compose 工作台：文本输入、语速/音量/音调滑块、系统音色选择、朗读与停止
- 分享文本直接朗读（`ACTION_SEND` / `text/plain`）
- 角色、替换规则、最近文本本地持久化（SharedPreferences）
- 读取系统已安装语音引擎的音色列表

## 尚未实现（不要误认为可用）

- **不是系统 TTS 引擎**：系统设置里的"语音合成"看不到本应用
- "搜狗语音 / 快速网络 / 滴滴语音"只是界面标签，实际全部走系统默认引擎
- 角色与替换规则只做字符串保存，**从未参与合成**
- 无多音色分角色朗读、无本地模型、无前台服务/通知/QS 磁贴

## 目录

```
app/src/main/java/org/nobody/multitts/replica/
├── MainActivity.kt          # 入口，处理分享文本
├── data/                    # SpeechController（系统 TTS 客户端）、SpeechVoice
├── ui/                      # Compose 界面与 MainViewModel
└── ui/theme/                # 配色与主题
docs/REIMPLEMENTATION_TARGET.md   # 原版逆向结论与分期目标
scripts/                     # build.ps1 / install.ps1
```

## 构建

前置：JDK 17+、Android SDK（`platforms/android-37.0`、`build-tools/36.x`）、已预热的 Gradle 离线缓存。

```powershell
./scripts/build.ps1                       # 默认 :app:assembleDebug
./scripts/build.ps1 -Task :app:assembleRelease
./scripts/build.ps1 -Clean -NoOffline     # 需要联网拉依赖时
```

脚本会自动解析 `ANDROID_HOME`、`GRADLE_USER_HOME`（默认 `%LOCALAPPDATA%\MultiTTS\gradle-home`）、
`GRADLE_RO_DEP_CACHE` 并生成 `local.properties`，不再需要手工 export。

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 构建必须提权的原因

Gradle 启动器需要 JDK 的 NIO selector；JDK 21 在 Windows 上用 AF_UNIX（命名管道）建立回环连接。
受限沙箱会拒绝该调用，表现为：

```
java.io.IOException: Unable to establish loopback connection
```

这不是项目配置问题，`--no-daemon`、换 SelectorProvider 均无效。构建时必须以
`danger-full-access` 权限执行 `scripts/build.ps1`。

## 安装

```powershell
./scripts/install.ps1 -Rebuild     # 构建 + 安装 + 启动
./scripts/install.ps1              # 只安装现有 APK
```

设备签名不一致时脚本会提示先 `uninstall`，因为旧的复刻版安装包使用了不同的调试签名。

## 已知限制

- 无测试依赖（离线缓存中缺少 JUnit/AndroidX Test），单测需联网补齐后再接入
- `values/strings.xml` 与 `values-zh-rCN/strings.xml` 内容相同，默认语言仍是中文
- 真机为 MIUI 且常处于锁屏，`adb shell input` 被系统拒绝，UI 自动化需先解锁或改用模拟器

## 后续规划

分阶段目标见 [`docs/REIMPLEMENTATION_TARGET.md`](docs/REIMPLEMENTATION_TARGET.md)：
P0 引擎注册 → P1 工作台收口 → P2 数据层与 CRUD → P3 语义合成（分句/角色/多音色）→ P4 系统体验 → P5 在线扩展。

当前 P0 采用**委托代理**策略：`TextToSpeechService` 把请求转发给设备上已安装的其他引擎，
先把引擎契约、参数映射与真机链路打通；本地模型引擎作为独立模块后续接入。
