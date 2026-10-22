package com.nomily.app.core.config

/**
 * The "one-prompt setup" for the two self-hosted providers (local faster-whisper server and Ollama): a prompt the user
 * pastes into ChatGPT / Claude Code on a computer, which installs both and prints the values to enter in the app.
 *
 * The prompt bakes in the wire contract the app speaks — batch `POST :12301/v1/transcribe` with raw audio bytes,
 * stream `ws://:12300/v1/listen` with raw Opus packets, Ollama's OpenAI-compatible API on the LAN — so the generated
 * server can't drift from the client. The text is shared verbatim with the other clients.
 */
object SetupAssistant {
    /** Chinese unless an explicit non-Chinese UI language is set: `null` (follow system) counts as Chinese, the primary audience. */
    fun prompt(appLanguage: String?): String =
        if (appLanguage != null && !appLanguage.startsWith("zh")) EN_PROMPT else ZH_PROMPT

    const val ZH_PROMPT = """请帮我把这台电脑部署成 Nomily AI 录音 App 的本地语音服务，做完后把要填进 App 的信息打印给我。分三步：
第一步，安装并启动 Ollama，设置开机自启并允许局域网访问（OLLAMA_HOST=0.0.0.0），再拉一个适合本机内存的中文对话模型（内存 8G 用 qwen2.5:3b，16G 及以上用 qwen2.5:7b）。
第二步，用 pip 安装 faster-whisper，写一个 Python 服务（FastAPI + uvicorn + websockets）并在后台常驻（macOS 用 launchd，Windows/Linux 用 systemd 或任务计划），同时放行防火墙的 12300 和 12301 端口。它要实现两个接口：
· HTTP 批量转录：监听 12301 端口，POST /v1/transcribe，请求体是原始音频字节（Content-Type: application/octet-stream，一般是 Ogg-Opus 容器，用 faster-whisper 直接解码），返回 JSON：{"text": "完整文本", "segments": [{"start": 开始秒, "end": 结束秒, "text": "分段文本"}]}。
· WebSocket 实况流：监听 12300 端口，路径 /v1/listen。客户端会持续发来等长的 Opus 裸包（二进制帧，不带容器头，需要按固定包长拼接解码成 PCM 再喂给 faster-whisper 的 VAD 流式识别），服务器每识别出一段就用 JSON 文本帧回发 {"text": "这段文字", "is_final": true 或 false}；收到 {"type":"CloseStream"} 文本帧时冲刷剩余音频、补发最后一段，然后关闭连接。
第三步，全部跑通后打印四行信息：① 这台电脑的局域网 IP（用 ifconfig 或 ipconfig 查，192.168 或 10 开头的那个）；② Nomily AI「设置 → 转录 → 本地 ASR 服务」里要填的主机名（就是上面的局域网 IP）；③ 该页要填的端口（固定填 12300）；④ 「设置 → AI 服务 → Ollama」里要填的地址（http://局域网IP:11434）和模型名。最后提醒我：手机和电脑必须连同一个 Wi-Fi，App 两个页面里的验证按钮都通过才算部署成功。"""

    const val EN_PROMPT = """Set up this computer as the local speech backend for the Nomily AI recorder app, then print exactly what I should type into the app. Three steps:
1. Install and start Ollama, enable auto-start, expose it to the LAN (OLLAMA_HOST=0.0.0.0), and pull a chat model that fits this machine's RAM (qwen2.5:3b for 8 GB, qwen2.5:7b for 16 GB+).
2. pip-install faster-whisper and write a Python service (FastAPI + uvicorn + websockets) that runs at login (launchd on macOS, systemd/Task Scheduler elsewhere) with firewall ports 12300 and 12301 open. It must implement:
· HTTP batch transcription: listen on port 12301, POST /v1/transcribe, request body is raw audio bytes (Content-Type: application/octet-stream, usually an Ogg-Opus container that faster-whisper decodes directly), respond with JSON: {"text": "full text", "segments": [{"start": sec, "end": sec, "text": "segment"}]}.
· WebSocket live stream: listen on port 12300 at path /v1/listen. The client streams equal-length raw Opus packets (binary frames, no container header — reassemble by fixed packet length, decode to PCM, feed a streaming VAD + faster-whisper). For each recognized segment reply with a JSON text frame {"text": "...", "is_final": true or false}; on receiving the text frame {"type":"CloseStream"}, flush remaining audio, emit the final segment, and close.
3. When everything runs, print four lines: (1) this computer's LAN IP (from ifconfig/ipconfig, the 192.168.x or 10.x one); (2) the host to enter in Nomily AI under Settings → Transcription → Local ASR server (that LAN IP); (3) the port for that page (always 12300); (4) the address for Settings → AI Providers → Ollama (http://LAN-IP:11434) and the model name. Remind me the phone and this computer must be on the same Wi-Fi, and deployment is done only when both verify buttons in the app pass."""
}
