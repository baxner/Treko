# Treko AI Assistant para Android 🤖✨

**Treko Assistant** es la aplicación nativa oficial de inteligencia artificial conversacional para **Android**, construida sobre el ecosistema **Xiaozhi**, con diseño cibernético en **Jetpack Compose**, audio de ultra baja latencia con **Google Oboe C++** y detección de palabra de activación local ("Treko") mediante **Sherpa-onnx**.

---

## 🌟 Características Principales

1. **Excelencia Visual y Cara Reactiva Treko**:
   - Renderizado acelerado de las **26 animaciones GIF oficiales** de Treko extraídas del firmware.
   - Transición fluida según estado conversacional (`idle` ➔ relax, `listening` ➔ escucha, `thinking` ➔ piensa, `speaking` ➔ feliz).
   - **Ecualizador reactivo en tiempo real**: Canvas con 5 barras de gradiente neon que oscilan sincronizadas con el volumen RMS del audio.

2. **Detección Local de la Palabra "Treko" (KWS)**:
   - Motor neuronal **Sherpa-onnx AAR** con modelos Zipformer Transducer en ONNX.
   - Sensibilidad ajustada (`score=3.0`, `threshold=0.05`) con boosting fonético en español e inglés.
   - Consumo mínimo de CPU (<2.5% en Cortex-A55) y batería.

3. **Audio de Ultra Baja Latencia**:
   - Motor nativo C++ NDK con **Google Oboe / AAudio** en modo `Exclusive MMAP` (<20ms).
   - Códec **Opus** nativo para streaming bidireccional continuo.

4. **Ecosistema MCP y Control de Hogar**:
   - Integración nativa con **Home Assistant** en red local privada (soporte CCT para LEDs blancos físicos dedicados a 2700K / 4000K / 6000K sin simulación RGB).
   - Suite completa de herramientas de productividad: Seguimiento de Hábitos, Tareas del Día, Temporizador Pomodoro y Recordatorios.

5. **Experiencia del Sistema Android**:
   - Servicio en segundo plano (`ForegroundService`) con notificación interactiva.
   - Integración como **Asistente Oficial de Android** (`VoiceInteractionService`).
   - Soporte **Bilingüe** completo (Español e Inglés).

---

## 🚀 Compilación en la Nube con GitHub Actions (CI/CD)

No necesitas instalar el SDK ni el NDK de Android en tu máquina local. Cada vez que subas cambios al repositorio en GitHub:

1. El workflow `.github/workflows/android-build.yml` se activa automáticamente en servidores cloud de GitHub.
2. Compila el código C++, enlaza librerías, empaqueta los assets y construye el APK debug.
3. El archivo `TrekoAssistant-debug.apk` queda listo para descargar en la pestaña **Actions > Artifacts** de tu repositorio en GitHub para instalar directamente en tu teléfono.

---

## 📂 Arquitectura Modular

```
treko-android/
├── .github/workflows/android-build.yml  # Pipeline CI/CD en la nube
├── app/                                 # Jetpack Compose UI, Servicios Android y Temas
├── core-audio/                          # Google Oboe C++ NDK y audio de baja latencia
├── core-kws/                            # Sherpa-onnx KWS + Modelos Zipformer ONNX
├── core-network/                        # OkHttp WebSockets binarios + MQTT + UDP
├── core-mcp/                            # Motor JSON-RPC 2.0 + Home Assistant REST + Room
└── core-common/                         # Modelos de dominio compartidos y StateFlow
```
