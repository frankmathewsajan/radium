# Radium

**The Off-Grid Signaling Transceiver.**

Radium is a zero-dependency, internet-free encrypted messaging application for Android. It operates exclusively on the cellular **Control/Signaling Plane** using binary Data SMS PDUs, bypassing the standard IP data layer entirely. Messages are encrypted with AES-256-GCM at the NDK layer and transmitted as raw binary packets through the baseband modem.

> No servers. No internet. No cloud. Just the radio.

---

## Table of Contents

- [Architecture](#architecture)
- [Security](#security)
- [How It Works](#how-it-works)
- [Building from Source](#building-from-source)
- [Installation](#installation)
- [Permissions](#permissions)
- [Known Limitations](#known-limitations)
- [Project Structure](#project-structure)
- [Future Plans](#future-plans)
- [License](#license)

---

## Architecture

Radium is built on a three-layer architecture that mirrors the physical radio stack:

```
┌─────────────────────────────────────────────┐
│              UI Layer (Kotlin)              │
│  WelcomeActivity → OnboardingActivity →    │
│  HomeActivity → ChatActivity               │
│  ViewBinding · RecyclerView · Room DB      │
├─────────────────────────────────────────────┤
│           Bridge Layer (Kotlin)             │
│  RadioEngine Singleton                     │
│  ┌─ dispatchDataSms()  (TX pipeline)       │
│  ├─ handleIncomingChunk() (RX pipeline)    │
│  ├─ 130-byte chunking with 3-byte header   │
│  └─ TelephonyCallback (signal telemetry)   │
├─────────────────────────────────────────────┤
│          Native Layer (C++ / NDK)          │
│  libradium.so                              │
│  ┌─ processDataNative()  → Encrypt + Zip  │
│  ├─ decodeDataNative()   → Decrypt + Unzip│
│  ├─ AES-256-GCM via BoringSSL (libcrypto) │
│  └─ zlib compression (pre-encryption)      │
└─────────────────────────────────────────────┘
         │                    ▲
         ▼                    │
┌─────────────────────────────────────────────┐
│     Android Baseband / RIL / Modem         │
│  SmsManager.sendDataMessage() → Port 8080  │
│  BroadcastReceiver ← DATA_SMS_RECEIVED     │
└─────────────────────────────────────────────┘
         │                    ▲
         ▼                    │
┌─────────────────────────────────────────────┐
│        Cell Tower (MSC / SS7 Core)         │
│  Physical Layer: LTE/5G NR signaling       │
└─────────────────────────────────────────────┘
```

### The AP/BP Split

On every mobile device, two processors run independently:

| Processor | Role | Radium's Use |
|-----------|------|-------------|
| **AP** (Application Processor) | Runs Android, apps, UI | Kotlin UI, Room DB, JNI bridge |
| **BP** (Baseband Processor) | Runs the radio firmware, manages RIL | Executes `sendDataMessage()`, receives PDUs |

Radium's `RadioEngine` singleton sits at the AP layer but communicates directly with the BP through Android's `SmsManager` API. The BP handles the actual RF transmission to the cell tower's MSC (Mobile Switching Center) via the SS7/Diameter signaling network.

### The JNI/NDK Bridge

All cryptographic operations happen in C++ to prevent reverse engineering via DEX decompilation:

```
Kotlin (AP)                    C++ (NDK)
    │                              │
    │  processDataNative(text)     │
    ├─────────────────────────────→│ 1. zlib compress
    │                              │ 2. Prepend 4-byte size header
    │                              │ 3. AES-256-GCM encrypt
    │                              │ 4. Prepend [12B IV][16B Tag]
    │  ←──── jbyteArray ──────────┤
    │                              │
    │  decodeDataNative(blob)      │
    ├─────────────────────────────→│ 1. Extract IV + Tag
    │                              │ 2. AES-256-GCM decrypt + verify
    │                              │ 3. Extract original size
    │  ←──── jstring ─────────────┤ 4. zlib decompress
```

The JNI boundary uses `jbyteArray` for raw byte manipulation, avoiding UTF-8/JNI string encoding corruption.

---

## Security

### Encryption Pipeline

| Step | Operation | Detail |
|------|-----------|--------|
| 1 | **Compression** | zlib deflate before encryption (reduces ciphertext size for 140-byte SMS limit) |
| 2 | **Size Header** | 4-byte big-endian original size prepended for decompression |
| 3 | **AES-256-GCM** | 256-bit key, 12-byte random IV, 16-byte authentication tag |
| 4 | **Wire Format** | `[IV:12B][Tag:16B][Ciphertext:NB]` — minimum 28 bytes overhead |

### Threat Model

- **Eavesdropping** — AES-256-GCM provides authenticated encryption. Even if the SS7 core is compromised, the payload is opaque.
- **Tampering** — The 16-byte GCM authentication tag detects any bit-flip (common in high-noise radio environments).
- **Reverse Engineering** — Cryptographic logic lives in `libradium.so` (compiled C++), not in DEX bytecode.
- **On-Device Storage** — All messages are stored as encrypted blobs in Room DB. Plaintext never touches disk.

### Current Limitations (Alpha)

- Pre-shared symmetric key (hardcoded). Key exchange protocol planned for v2.
- No forward secrecy. Compromising the key compromises all past messages.

---

## How It Works

### Sending a Message

1. User types text in `ChatActivity`
2. `RadioEngine.processDataNative(text)` → zlib compress → AES-256-GCM encrypt → `ByteArray`
3. Encrypted blob stored in Room DB (status: `PENDING`)
4. Blob chunked into 130-byte segments with 3-byte header: `[MsgID][TotalParts][PartNum]`
5. Each chunk dispatched via `SmsManager.sendDataMessage()` to port **8080**
6. `BroadcastReceiver` listens for `SMS_SENT` / `SMS_DELIVERED` → updates Room status

### Receiving a Message

1. `DATA_SMS_RECEIVED` broadcast fires on port 8080
2. `RadioEngine.handleIncomingChunk()` buffers chunks by `MsgID`
3. When all chunks arrive, blob reassembled → `decodeDataNative()` → plaintext
4. Re-encrypted for storage → Room DB insert → UI refresh

### The 140-Byte Constraint

A single GSM/LTE signaling packet holds **140 bytes**. Android's `sendDataMessage()` adds its own port UDH, so Radium uses **130 bytes** per chunk with a 3-byte application header, leaving 7 bytes of margin for the UDH.

---

## Building from Source

### Prerequisites

| Tool | Version |
|------|---------|
| Android Studio | Ladybug or later |
| Android SDK | API 36 (compileSdk) |
| NDK | 28.2.13676358 |
| CMake | 3.22.1 |
| JDK | 11+ |
| Kotlin | 2.1.x |

### Steps

```bash
# Clone the repository
git clone https://github.com/frankmathewsajan/radium.git
cd radium

# Open in Android Studio
# File → Open → select the radium directory

# Sync Gradle (automatic on open)
# The NDK, CMake, and OpenSSL prefab will be resolved automatically

# Build debug APK
./gradlew assembleDebug

# The APK will be at:
# app/build/outputs/apk/debug/app-debug.apk
```

### NDK & OpenSSL

Radium uses [BoringSSL](https://boringssl.googlesource.com/boringssl/) via Android's Prefab system. The OpenSSL package is declared in `libs.versions.toml` and linked through CMake's `find_package(openssl REQUIRED CONFIG)`. No manual NDK configuration is needed — Gradle handles the prefab download.

---

## Installation

### From Release (Recommended)

1. Download `radium-v0.1.0-alpha.apk` from the [Releases](https://github.com/frankmathewsajan/radium/releases) page
2. Transfer to your Android device
3. Enable **Install from Unknown Sources** in Settings
4. Open the APK and install
5. Launch Radium and walk through the permission onboarding

### From Source

```bash
./gradlew installDebug
```

### Requirements

- Android 10+ (API 29)
- Active SIM card with cellular service
- Two devices for testing (loopback drops — see Limitations)

### Post-Install Setup

After granting all permissions, you **must** manually enable:

> **Settings → Apps → Radium → Mobile Data → Premium SMS Access → Always Allow**

Without this, Android silently blocks headless SMS transmission.

---

## Permissions

Radium requests 5 permissions through an explain-then-request onboarding flow:

| Permission | Why |
|-----------|-----|
| `SEND_SMS` | Transmit encrypted binary packets over the signaling plane |
| `RECEIVE_SMS` | Listen for incoming data SMS on port 8080 |
| `READ_PHONE_STATE` | Monitor RSRP/RSRQ/SNR signal telemetry before transmission |
| `ACCESS_FINE_LOCATION` | Cell tower identification (PCI, EARFCN) for radio diagnostics |
| `READ_CONTACTS` | Select recipients from the phonebook (never uploaded anywhere) |

---

## Known Limitations

- **No Self-Messaging** — Modems drop binary SMS where sender = receiver (SS7 loopback prevention). Testing requires two separate SIM cards.
- **140-Byte Ceiling** — Large messages are chunked, adding latency proportional to message size.
- **PMIC Throttling** — At critically low battery (<5%), the power amplifier may be throttled, causing `RESULT_ERROR_GENERIC_FAILURE`.
- **Pre-Shared Key** — Current alpha uses a hardcoded symmetric key. Not suitable for production use.
- **No Internet Fallback** — By design. If there's no cellular signal, messages cannot be sent.
- **OEM Variability** — Some OEMs aggressively kill background receivers. MIUI, ColorOS, and OneUI may require manual battery optimization exclusion.

---

## Project Structure

```
radium/
├── app/
│   ├── src/main/
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt          # NDK build config
│   │   │   └── native-lib.cpp          # AES-256-GCM + zlib (C++)
│   │   ├── java/com/example/radium/
│   │   │   ├── WelcomeActivity.kt      # Welcome gate screen
│   │   │   ├── OnboardingActivity.kt   # Permission onboarding flow
│   │   │   ├── PermissionSlideFragment.kt
│   │   │   ├── HomeActivity.kt         # Conversation list + contact picker
│   │   │   ├── ChatActivity.kt         # Message UI + telemetry + radio TX/RX
│   │   │   ├── RadioEngine.kt          # Singleton: JNI bridge + SMS dispatch
│   │   │   ├── ConversationAdapter.kt  # RecyclerView adapter
│   │   │   ├── MessageAdapter.kt       # Chat bubble adapter
│   │   │   └── data/
│   │   │       ├── RadiumDatabase.kt   # Room database singleton
│   │   │       ├── MessageEntity.kt    # Encrypted message storage
│   │   │       ├── MessageDao.kt       # Data access queries
│   │   │       └── ConversationEntity.kt
│   │   ├── res/
│   │   │   ├── layout/                 # XML layouts (edge-to-edge)
│   │   │   ├── drawable/               # Vector icons + glassmorphism shapes
│   │   │   └── values/                 # Colors, strings, themes
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts                # App-level dependencies
├── build.gradle.kts                    # Root project config
├── settings.gradle.kts
├── gradle/libs.versions.toml           # Version catalog
└── radium-agent-skills.md              # AI agent skill profile
```

---

## Future Plans

### v0.2 — Key Exchange
- Diffie-Hellman key exchange over the signaling plane
- Per-conversation symmetric keys derived from shared secret
- Key rotation on configurable interval

### v0.3 — Forward Secrecy
- Double Ratchet protocol (Signal-style)
- Ephemeral key pairs per message session
- Break-in recovery after key compromise

### v0.4 — Group Channels
- Multi-party broadcast over port 8080
- Fan-out chunking with shared group key
- Member management via signed control packets

### v0.5 — Hardened Distribution
- ProGuard/R8 obfuscation enabled
- Native code stripping and symbol removal
- Certificate pinning for update verification
- Reproducible builds

### Stretch Goals
- Mesh relay via nearby devices (Wi-Fi Direct)
- Steganographic encoding in standard SMS text
- Hardware security module (HSM) key storage via Android Keystore
- Baseband-level packet injection (requires root + custom RIL)

---

## License

This project is provided as-is for research and educational purposes.

---

*Built with bare metal intent.*
