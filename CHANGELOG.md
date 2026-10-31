# Changelog

## 1.0.0
First release of Speech to Chat, a fork of Speech To Text Accessibility for Minecraft 26.3.

- Live preview above the hotbar while you talk; sends when you let go.
- Bundled speech engine (whisper.cpp) for Windows, Linux and macOS: Vulkan on any GPU, Metal on Mac, CPU fallback.
  Runs as a separate process, so it can never crash the game, and closes with the game.
- First-run setup screen: recommends a model for your PC and downloads it with checksum verification.
- Live preview on a small CPU model while the graphics card does the final text, so it doesn't cost frames.
- Microphone picker, 99 languages (follows your game language), optional translation to English.
- Long speech is sent as several messages with a pause between them; recording stops by itself after 2 minutes.
