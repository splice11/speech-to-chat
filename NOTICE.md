# Credits and third-party licences

This mod is a fork of **Speech To Text Accessibility** by **Lunaposs** and **Orion**
(GPL-3.0, originally at codeberg.org/lunaposs/Minecraft-STTA-Mod, published on Modrinth as `stta`).
The original source repository is no longer online; this fork was rebuilt from the GPL-3.0 2.2.0 release
and has since been largely rewritten. It remains under the GNU GPL v3 (see `LICENSE`).

Bundled or downloaded components:

| Component | Use | Licence |
|---|---|---|
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) / ggml (pinned in `engine/whisper.cpp`, patched by `engine/patches`) | speech engine (`whisper-server`) bundled for Windows, Linux, macOS | MIT |
| [Silero VAD](https://github.com/snakers4/silero-vad) v6.2.0, ggml conversion from [ggml-org/whisper-vad](https://huggingface.co/ggml-org/whisper-vad) | voice-activity detection, bundled (`src/main/resources/models`) | MIT |
| OpenAI Whisper models (ggml conversions from [ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp)) | speech models, downloaded on first use with SHA-256 checks | MIT |
| Vulkan loader (system) | GPU access on Windows/Linux | Apache-2.0 |
