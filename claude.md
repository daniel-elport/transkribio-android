# 🎙️ Android Live Speech Transcription App
**Whisper-based • Offline • Kotlin • German-first**

## 📌 Project Overview

Build a **native Android application** using **Kotlin + Android Studio** that performs **live speech-to-text transcription directly on the device**.

The app focuses on:
- **Low-latency live transcription**
- **Offline inference**
- **German language first**
- **Clean architecture**
- **Modern, minimal, premium UI**

This is **NOT** a cloud-based solution.  
All speech recognition runs **locally on-device**.

---

## 🎯 Core Goals (MVP)

### Functional
- 🎤 Record audio from the device microphone
- 🧠 Perform **live transcription** while the user is speaking
- 🇩🇪 German transcription (`language = "de"`)
- 🧾 Display:
  - **live partial results**
  - **final transcription after stop**
- ⏹️ Start / Stop recording cleanly

### Non-Functional
- ⚡ Low latency (near real-time feel)
- 📱 No UI blocking (strict background processing)
- 🔋 Reasonable battery usage
- 📴 Fully offline

---

## 🧠 Speech Recognition Strategy

Whisper is **not truly streaming** by design.  
“Live” transcription is achieved via:

- Continuous microphone recording
- Chunk-based decoding
- Voice Activity Detection (VAD)
- Incremental decoding results

Avoid:
- Long fixed chunks (e.g. 30s)
- UI thread blocking
- Decoding silence (hallucinations)

---

## 🏆 Chosen Engine: Sherpa-ONNX

**Sherpa-ONNX is mandatory.**

### Why
- Built for streaming ASR
- Handles buffering + VAD
- Optimized for mobile CPUs
- Clean Kotlin API
- No JNI boilerplate

---

## 🔧 Tech Stack

- Android
- Kotlin
- Android Studio
- Sherpa-ONNX (Whisper Online Recognizer)
- Whisper Tiny German (ONNX)
- AudioRecord (PCM 16-bit, 16kHz)
- Kotlin Coroutines

---

## 🧩 Architecture

```
UI (Compose / Views)
        ↓
ViewModel (StateFlow)
        ↓
Audio Loop (Coroutine)
        ↓
Sherpa-ONNX Recognizer
```

Rules:
- No main-thread audio or inference
- Singleton recognizer
- Clean lifecycle handling

---

## 🎤 Audio Requirements

- Sample rate: 16000 Hz
- Mono
- PCM 16-bit
- Convert to FloatArray

Loop:
- Continuous read
- Immediate feed
- Decode when ready
- Emit partial results

---

## 🧠 Whisper Model

- Model: Whisper Small (INT8 quantized)
- Language: German
- Task: Transcription

Assets:
- small-encoder.int8.onnx
- small-decoder.int8.onnx
- small-tokens.txt
- silero_vad.onnx

No runtime downloads.

---

## 🖥️ UI / UX Principles

- Minimal
- Calm
- Large readable typography

Components:
- Big mic button
- Live transcription area
- Listening indicator
- Final text persistence

---

## 🛑 Start / Stop Behavior

**Start**
- Init recorder
- Create stream
- Clear buffers

**Stop**
- Stop recording
- Final decode
- Release resources

---

## 🚫 Non-Goals (Current Phase)

- Cloud ASR
- Multi-language
- Translation
- Diarization
- File import
- Background recording

---

## 📦 Deliverables

- Kotlin Android app
- Live transcription
- Clean UI
- Stable lifecycle
- Readable code

---

## 🧭 Future Extensions

- Long recordings
- Export (PDF, DOCX, MD)
- LLM summarization
- Keyword extraction
- GDPR storage

---

## 🔨 Development Workflow

**Build & Install**: User handles build and install in Android Studio (not Claude).
Claude should make code changes only; do not run gradle build or adb install commands.

---

## ✅ Final Instruction

Build **exactly** according to this spec.
Favor stability, UX, and clarity over features.
