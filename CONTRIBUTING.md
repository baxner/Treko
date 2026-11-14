# Contributing to Treko Assistant

Thank you for helping improve **Treko Assistant**!

## Module Architecture

- `:app` — Jetpack Compose UI, screens, dialogs, animations, `TrekoAssistantService`, `TrekoTileService`, EN/ES string resources (`res/values/strings.xml`, `res/values-es/strings.xml`), and `proguard-rules.pro`.
- `:core-common` — Shared state models, `TrekoPreferences`, persistent managers (`TrekoHabitManager`, `TrekoTaskManager`, `TrekoTimerManager`), and `TrekoLogBuffer`.
- `:core-audio` — C++ NDK AAudio engine, `NativeAudioEngine`, `OpusAudioCodec`, and `AdaptiveVad`.
- `:core-kws` — Sherpa-ONNX wake-word detector, `KwsTokenizer`, `WakeWordVoiceTrainer`, and `WakeWordVoicePrint`.
- `:core-network` — `XiaozhiWebSocketClient` and `XiaozhiActivationManager` (OTA v2).
- `:core-mcp` — `McpDispatcher` and `HomeAssistantClient` (smart home, cameras, LLM Vision).

## Local Development Checks

Before opening a pull request, please verify unit tests, debug APK assembly, and Android Lint pass cleanly:

```bash
./gradlew testDebugUnitTest :app:assembleDebug :app:lintDebug
```

## Security & Open-Source Hygiene

- **Never commit** `local.properties`, keystores (`*.jks`, `*.keystore`), API tokens, private LAN IP addresses (`192.168.x.x`, `10.x.x.x`), personal entity names, or personal provider IDs.
- Keep user-facing strings localized in both `app/src/main/res/values/strings.xml` (English) and `app/src/main/res/values-es/strings.xml` (Spanish).
- Avoid adding large binary files (`*.aar`, `*.apk`) directly to Git; `local-repo/` uses `settings.gradle.kts` SHA-256-verified bootstrapping instead.
