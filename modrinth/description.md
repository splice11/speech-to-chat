Hold a key, speak, release: the text is sent to chat. Speech recognition runs locally with
[whisper.cpp](https://github.com/ggml-org/whisper.cpp); no audio or text leaves your computer.

## Features
- Live preview above the hotbar while you speak.
- Sends on release, or opens the chat box so you can edit first.
- GPU via Vulkan (Windows, Linux) or Metal (macOS); CPU otherwise.
- First-run setup measures your PC and picks a model that runs fast enough on it.
- 99 languages; optional translation to English.
- Long messages are split and spaced out to avoid spam kicks.

## Setup
Requires [Fabric API](https://modrinth.com/mod/fabric-api).
[Mod Menu](https://modrinth.com/mod/modmenu) and [YACL](https://modrinth.com/mod/yacl) are optional (settings).

On first launch, a setup screen downloads a speech model (60–575 MB). Then hold **V** in a world to talk.

## Downloads and processes
- Speech models are downloaded once, when you click "Download & set up", from
  `huggingface.co/ggerganov/whisper.cpp`, and checked against SHA-256 checksums.
- The speech engine (`whisper-server` from whisper.cpp) ships inside the jar, built by this project's
  public CI. It runs as a separate local process, listens on `127.0.0.1` only, and exits with the game.

## Compatibility
Client-side. Fabric, Minecraft 26.3. Windows 10/11 x64, Linux x64 (glibc 2.34+, Flatpak launchers
work), macOS 12+.

## Credits
Fork of [Speech To Text Accessibility](https://modrinth.com/mod/stta) by Lunaposs and Orion (GPL-3.0).
Engine: whisper.cpp (MIT). Voice detection: Silero VAD (MIT). Code written with AI assistance (Claude).
Source: [github.com/splice11/speech-to-chat](https://github.com/splice11/speech-to-chat).
