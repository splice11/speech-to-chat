# Speech To Text Accessibility (26.3 fork)

Hold a key, talk, and your words are typed into Minecraft chat.

- **Live preview** above the hotbar while you speak: words settle from grey to white as they are recognised.
- **Sends on release** (or opens the chat box with the text, if you prefer to edit first).
- **Runs on your own PC.** A bundled [whisper.cpp](https://github.com/ggml-org/whisper.cpp) engine recognises
  speech locally. No audio or text is uploaded anywhere; the only download is the speech model, once.
- **Any graphics card**: Vulkan on Windows and Linux (NVIDIA, AMD, Intel), Metal on macOS, CPU otherwise.
  With a graphics card, the live preview runs on a small CPU model so it doesn't take frames from the game.
- **Can't crash the game.** The engine is a separate process; if it fails, Minecraft keeps running and the mod
  falls back to the CPU. It exits with the game, even if the game is force-killed.
- 99 languages (it follows Minecraft's language by default), optional translation to English.

## Using it

1. Install with [Fabric API](https://modrinth.com/mod/fabric-api). [Mod Menu](https://modrinth.com/mod/modmenu) and
   [YACL](https://modrinth.com/mod/yacl) are optional (settings screen).
2. On first launch a setup screen recommends a model for your PC and downloads it
   (575 MB with a graphics card, 60–190 MB without).
3. In a world, hold **V** (rebindable) and talk. Let go to send.

| Model | Size | Good for |
|---|---|---|
| Accurate (large-v3-turbo) | 575 MB | any dedicated GPU, Apple Silicon |
| Balanced (small) | 190 MB | most CPUs, integrated graphics |
| Light (base) | 60 MB | old or slow PCs |

Files live in `<game dir>/speech-to-text/` (models, the extracted engine, engine logs).

### macOS
The first time you talk, macOS asks whether your launcher may use the microphone; allow it.

## Building

```sh
git clone --recursive <repo>
engine/build-linux-container.sh        # Linux engines (Ubuntu 22.04 in podman, like CI)
./gradlew build                        # jar in build/libs, with whatever engines are in engine/out
```
CI (`.github/workflows/build.yml`) builds the engines for Windows, Linux and macOS and packs all of them into one jar.

In-game test: `STTA_WAV=<16 kHz mono wav> ./gradlew runClientGameTest` walks through the setup screen, then
plays the WAV as if it were the microphone and checks the chat message.

## Licence
GPL-3.0. Fork of the original mod by Lunaposs and Orion; see [NOTICE.md](NOTICE.md) for credits and
third-party licences.
