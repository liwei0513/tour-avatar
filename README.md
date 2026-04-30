# Tour Avatar · 数字导览

> **面向博物馆 / 美术馆 / 文旅景区的离线 AI 数字导览员**
> Offline-first AI guide for museums, art galleries, and cultural sites — runs on Android tablets / all-in-one displays.

---

## ✨ 特性

| 模块 | 能力 |
| --- | --- |
| 🗣 语音输入（ASR） | sherpa-onnx 端侧识别，按住说话（PTT），自带 VAD |
| 🔊 语音输出（TTS） | sherpa-onnx 离线合成，**句级流式播报** —— LLM 一边出 token，TTS 一边读 |
| 🧠 大模型（LLM） | OpenAI 兼容 SSE 流式接口，支持 Ollama / OpenAI / 智谱 / 千帆 / Together / Groq |
| 📚 知识库（RAG） | SQLite + 中文分词 + BM25，离线检索展品/景点资料（接口已留，提供示例占位实现） |
| 🎭 3D 虚拟形象 | WebView + Three.js 程序化数字人，可换装为 three-vrm 真人化 VRM 模型 |
| 💬 实时对话 UI | 流式打字效果、滚动跟随、用户/导览员气泡区分、长按可复制 |
| 🗂 历史记录 | Room 持久化，按"展位 / 时间"自动归档，可继续对话 |
| 🌐 横屏一体机适配 | `minSdk 26`、`targetSdk 34`、强制 landscape，单 Activity 全屏 |

---

## 🏛️ 应用场景

- **博物馆 / 美术馆**：每件展品配一台一体机，访客按住说话，"这件作品的作者是谁""为什么有破损"，数字导览员实时回答
- **文旅景区**：景点入口设虚拟讲解员，回答路线 / 门票 / 文化背景问题
- **校园 / 企业展厅**：来访者交互式导览
- **高校博物馆**：结合学科知识库做研究性导览

---

## 🏗️ 架构

```
┌──────────────────────────────────────────────────────────────┐
│                      MainActivity (Landscape)                │
│  ┌─────────────────────┐    ┌────────────────────────────┐   │
│  │   Avatar WebView    │    │   Chat Panel               │   │
│  │   (Three.js +       │    │   ┌────────────────────┐   │   │
│  │    three-vrm)       │    │   │  Toolbar           │   │   │
│  │                     │    │   ├────────────────────┤   │   │
│  │   ┌───────────┐     │    │   │  Streaming         │   │   │
│  │   │  Avatar   │     │    │   │  RecyclerView      │   │   │
│  │   │  Bridge   │◀────┼────┤   │  (real-time text)  │   │   │
│  │   └───────────┘     │    │   ├────────────────────┤   │   │
│  └─────────────────────┘    │   │  Status + PTT btn  │   │   │
│                             │   └────────────────────┘   │   │
└─────────────────────────────┴────────────────────────────────┘
                              │
                              ▼
                  ┌─────────────────────────┐
                  │     ChatViewModel       │
                  │  (Dialog State Machine) │
                  └────────┬────────────────┘
            ┌──────────────┼──────────────┬──────────────┐
            ▼              ▼              ▼              ▼
       AsrManager     LlmClient      TtsManager     ChatRepository
      (sherpa-onnx)  (OpenAI SSE)  (sherpa-onnx)    (Room SQLite)
                          │                              │
                          ▼                              ▼
                    RagRetriever                   conversations
                  (BM25 + jieba)                    + messages
```

**对话状态机**：

```
IDLE ──[按住 PTT]──▶ LISTENING
                           │ [松开]
                           ▼
                       THINKING ──[首个 LLM token]──▶ SPEAKING
                                                          │ [流结束 + TTS 排空]
                                                          ▼
                                                        IDLE
```

---

## 🚀 快速开始

### 前置

- Android Studio Hedgehog (2023.1) 或更新
- JDK 17
- 一台 Android 8.0+（API 26+）设备，**横屏**显示效果最佳
- LLM 提供方：本地 Ollama（推荐）或任意 OpenAI 兼容服务

### 1. Clone & build

```bash
git clone https://github.com/liwei0513/tour-avatar.git
cd tour-avatar
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 2. 配置 LLM

打开 `app/src/main/kotlin/io/touravatar/util/AppConfig.kt`，按需修改：

```kotlin
var llmBaseUrl = "http://192.168.1.10:11434/v1"   // Ollama 局域网地址
var llmApiKey  = ""                                // Ollama 不需要
var llmModel   = "qwen2.5:7b"                      // 或 glm-4-flash / gpt-4o-mini
```

主流提供方示例：

| 提供方 | baseUrl | model |
| --- | --- | --- |
| **Ollama (LAN)** | `http://192.168.1.10:11434/v1` | `qwen2.5:7b` |
| **OpenAI** | `https://api.openai.com/v1` | `gpt-4o-mini` |
| **智谱 GLM** | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
| **百度千帆** | `https://qianfan.baidubce.com/v2` | `ernie-speed-128k` |

### 3. 接入真实 ASR / TTS（替换 Stub）

当前仓库自带 `StubAsrManager` / `StubTtsManager` 用于跑通 UI 流程。**生产部署**请按以下步骤接入 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)：

```bash
# 1. 下载预编译 native 库
mkdir -p app/src/main/jniLibs/arm64-v8a
# 下载 libsherpa-onnx-jni.so 等到该目录
# https://github.com/k2-fsa/sherpa-onnx/releases

# 2. 下载模型并放到 assets
mkdir -p app/src/main/assets/models/asr
mkdir -p app/src/main/assets/models/tts
# 放入 streaming-zipformer (ASR) / VITS 或 Matcha-TTS (TTS) 模型

# 3. 实现 SherpaAsrManager.kt / SherpaTtsManager.kt
# 参考 voice/StubAsrManager.kt / voice/StubTtsManager.kt 顶部的 TODO 注释
```

### 4. 接入真实 3D 形象（VRM）

`assets/avatar/avatar.js` 默认渲染一个程序化卡通形象。要换成真人化 VRM：

1. 在 [VRoid Hub](https://hub.vroid.com/) 或 [Booth](https://booth.pm/) 下载 `.vrm` 模型
2. 把模型重命名为 `model.vrm` 放到 `app/src/main/assets/avatar/`
3. 在 `avatar.js` 取消注释 VRM loader 块，注释掉 `buildProceduralAvatar()`

---

## 📁 目录结构

```
tour-avatar/
├── app/
│   ├── src/main/
│   │   ├── kotlin/io/touravatar/
│   │   │   ├── MainActivity.kt           ★ 主入口（横屏 / PTT / WebView）
│   │   │   ├── HistoryActivity.kt        历史对话
│   │   │   ├── chat/
│   │   │   │   ├── DialogState.kt        对话状态机
│   │   │   │   └── ChatViewModel.kt      ★ 编排：ASR → LLM → TTS
│   │   │   ├── voice/
│   │   │   │   ├── AsrManager.kt         接口
│   │   │   │   ├── StubAsrManager.kt     占位（替换为 SherpaAsrManager）
│   │   │   │   ├── TtsManager.kt         接口（句级流式合成）
│   │   │   │   └── StubTtsManager.kt     占位
│   │   │   ├── llm/
│   │   │   │   ├── LlmClient.kt          接口
│   │   │   │   ├── ChatMessage.kt        DTO
│   │   │   │   └── OpenAiCompatibleClient.kt   ★ SSE 流式
│   │   │   ├── data/
│   │   │   │   ├── AppDatabase.kt        Room 数据库
│   │   │   │   ├── ConversationEntity.kt
│   │   │   │   ├── MessageEntity.kt
│   │   │   │   └── ChatRepository.kt     ★ 数据仓库
│   │   │   ├── rag/
│   │   │   │   ├── RagRetriever.kt       接口
│   │   │   │   └── InMemoryRagRetriever.kt   占位（生产换 BM25 + jieba）
│   │   │   ├── ui/
│   │   │   │   ├── AvatarBridge.kt       ★ Kotlin ↔ JS 桥
│   │   │   │   ├── ChatAdapter.kt        流式聊天列表
│   │   │   │   └── HistoryAdapter.kt
│   │   │   └── util/
│   │   │       ├── AppConfig.kt          ★ 全局配置
│   │   │       └── Logging.kt
│   │   ├── assets/avatar/
│   │   │   ├── index.html                WebView 入口
│   │   │   ├── style.css                 馆藏配色
│   │   │   └── avatar.js                 ★ Three.js 程序化数字人
│   │   ├── res/
│   │   │   ├── layout/                   ConstraintLayout 横屏布局
│   │   │   ├── values/                   主题、配色、字符串
│   │   │   └── drawable/                 PTT 按钮、消息气泡
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/wrapper/gradle-wrapper.properties
├── README.md  (this file)
└── LICENSE    (MIT)
```

---

## 🛣️ 路线图

- [x] 横屏 UI + Avatar WebView + 聊天列表 + PTT
- [x] OpenAI 兼容流式 LLM 客户端
- [x] Room 持久化 + 历史对话页
- [x] 句级流式 TTS 接口
- [x] 程序化 3D 数字人（Three.js）
- [ ] sherpa-onnx 真实 ASR / TTS 集成示例
- [ ] BM25 + jieba 真实 RAG 实现
- [ ] VRM 模型替换 + 表情驱动
- [ ] 多语言支持（英 / 日 / 韩，面向境外游客）
- [ ] 展品 NFC / 二维码触发对话
- [ ] 馆方后台：知识库管理 + 对话审计

---

## 📜 License

MIT — see [LICENSE](LICENSE).

部署 / 商务合作请提 issue。
