**Hold a key, talk, and your words are typed into chat.** Your speech is recognised on your own PC: no account, no cloud, nothing uploaded.

## Features

- **Live preview** above the hotbar while you speak. Words settle from grey to white as they are recognised.
- **Sends when you let go** of the key, or opens the chat box with the text if you prefer to check it first.
- **Accurate.** Uses OpenAI's Whisper models (large-v3-turbo by default with a graphics card) through
  [whisper.cpp](https://github.com/ggml-org/whisper.cpp). Typically under a second from letting go to the message in chat.
- **Any graphics card:** Vulkan on Windows and Linux (NVIDIA, AMD, Intel), Metal on macOS, CPU on everything else.
  With a graphics card, the live preview runs on a small CPU model so it doesn't take frames from the game.
- **99 languages.** Follows your Minecraft language by default; can translate everything you say to English.
- **Can't crash your game.** Recognition runs in a separate process. If it fails, Minecraft keeps running and the mod
  falls back to the CPU. It closes with the game, even if the game is force-quit.
- Microphone picker, long speech split into several messages with a pause between them (no spam kicks),
  automatic tuning for slow PCs, unloads the model when you haven't talked for a while.

## Getting started

1. Install [Fabric API](https://modrinth.com/mod/fabric-api). [Mod Menu](https://modrinth.com/mod/modmenu) +
   [YACL](https://modrinth.com/mod/yacl) are optional, for the settings screen.
2. Start the game. A setup screen recommends a speech model for your PC and downloads it once.
3. In a world, hold **V** (rebindable in Controls) and talk.

| Model | Download | Good for |
|---|---|---|
| Accurate (large-v3-turbo) | 575 MB | any dedicated graphics card, Apple Silicon |
| Balanced (small) | 190 MB | most CPUs and integrated graphics |
| Light (base) | 60 MB | older or slow PCs |

## What this mod downloads and runs

To be fully transparent:

- **Speech models** are downloaded **once, when you click "Download & set up"**, from the official whisper.cpp model
  repository on HuggingFace (`huggingface.co/ggerganov/whisper.cpp`). Every file is checked against a SHA-256 checksum
  before use. Nothing else is downloaded.
- **The speech engine** (`whisper-server` from whisper.cpp, with a small patch) is **inside the mod jar**, built from
  source by the public GitHub Actions workflow of this project for Windows, Linux and macOS. The mod copies it to
  `<game folder>/speech-to-chat/engine` and runs it as a local process that only listens on `127.0.0.1`.
- **No data leaves your computer.** Audio goes from your microphone to that local process and the text goes to your chat.

## Compatibility

Client-side only, works on any server. Fabric, Minecraft 26.3. Windows 10/11 (x64), Linux (x64, glibc 2.34+,
Flatpak launchers included), macOS 12+ (Apple Silicon and Intel).

## Credits

A fork of [Speech To Text Accessibility](https://modrinth.com/mod/stta) by **Lunaposs** and **Orion**, rewritten for
26.3. Licensed GPL-3.0 like the original. Speech engine: whisper.cpp (MIT). Voice activity detection: Silero VAD (MIT).
Source code: [github.com/splice11/speech-to-chat](https://github.com/splice11/speech-to-chat).
