# AndroidLocalLLM

完全离线运行的 Android 本地大模型助手：拍照 → OCR → llama.cpp 端内推理 → 多轮对话，全程无需联网。

> 基于 llama.cpp（JNI 加载本地 `.gguf` 模型），支持思考模式（CoT）、流式输出与 LaTeX 公式渲染。

## 功能特性

- **完全离线**：模型与推理全部在本机完成，不上传任何数据
- **拍照解题**：CameraX 拍照 → uCrop 裁剪 → MLKit 端内中英文 OCR → 可编辑确认 → 对话解答
- **本地推理**：直接加载 `/sdcard/AIModel/` 下的 `.gguf` 模型（llama.cpp 原生 `common_sampler` 采样）
- **思考模式（CoT）**：
  - 工具栏全局思考开关，切换即时生效、无需重载模型
  - 单条消息前缀指令 `/think` / `/no_think` 强制覆盖
  - 思考预算（token 上限）可调，思考/回答实时分流显示、可折叠
- **推理参数可调**：温度、Top-p、Top-k、存在/频率惩罚、停止序列、最大生成长度、上下文长度、线程数
- **会话管理**：Room 持久化历史会话，抽屉列表、点击恢复、滑动删除
- **LaTeX 公式**：聊天流内内联/行间公式渲染

## 系统要求

- Android 8.0+（API 26），`arm64-v8a`
- 模型：任意 llama.cpp 兼容的 `.gguf`（推荐思考型模型如 Qwen3，量化建议 Q4_K_M）

## 使用方法

1. 安装 APK 并授予"所有文件访问"权限
2. 将 `.gguf` 模型放入 `/sdcard/AIModel/`
3. 在抽屉 → 设置 → 选择模型
4. 拍照或直接输入提问

## 构建

```bash
# Debug（含 x86_64 模拟器支持）
gradlew.bat :app:assembleDebug

# Release（仅 arm64-v8a）
gradlew.bat :app:assembleRelease
```

依赖：Android Studio + NDK 27 + CMake 3.22.1。llama.cpp 源码随仓库内置（`app/src/main/cpp/llama.cpp`）。

## 技术栈

Kotlin · llama.cpp (JNI/C++) · CameraX · MLKit OCR · uCrop · Room · Coroutines/Flow · JLaTeXMath

## 架构

```
app/src/main/
 ├─ java/com/ueiol/scanaianswer/
 │   ├─ data/          Room 会话库、OCR、模型仓库、推理设置
 │   ├─ llm/           LlamaService（生成流、思考状态机）
 │   └─ ui/            拍照 / 确认 / 聊天 / 设置
 ├─ java/com/ueiol/liteai/llm/
 │   └─ LlamaBridge.kt JNI 桥（与 libliteai.so 符号一一对应）
 └─ cpp/
     ├─ llama-android.cpp   有状态增量式推理 + 停止序列 + 思考模板
     └─ llama.cpp/          上游源码（add_subdirectory 构建）
```

## 许可证

本项目基于 [PolyForm Noncommercial License 1.0.0](./LICENSE) 开源 —— 允许个人学习、研究与非商业使用；商业使用需另行获得作者授权。
