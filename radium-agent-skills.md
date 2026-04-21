# Agent Skill Profile: Radium Telecommunications Architecture

## 1. Skill Overview
**Skill Name:** `radium_telephony_framework`
**Description:** Grants the agent deep architectural knowledge of bare-metal Android telecommunications, signaling plane packet injection, Baseband/RIL (Radio Interface Layer) interactions, NDK-level cryptographic payload generation, and physical radio constraints.
**Target Agent:** Systems Architect / Security Researcher AI Coder.

## 2. Core Architecture: The "Radium" Paradigm
Radium is a closed-loop cryptographic transceiver. It bypasses the standard Internet/Data Plane (IP layer) entirely, communicating exclusively over the cellular **Control/Signaling Plane** using binary Data SMS PDUs (Protocol Data Units) targeted at arbitrary application ports (e.g., Port 8080).

### Key Differentiators:
* **Net-Independent:** Operates without an active mobile data connection or Wi-Fi. It relies purely on the baseband modem's connection to the cell tower's MSC (Mobile Switching Center).
* **Zero-Click Vector Capability:** By targeting specific ports, the payloads bypass the standard SMS inbox UI. The OS routes the binary data directly to the listening application buffer.
* **Metal-Layer Telemetry:** Radium actively monitors RSRP (Reference Signal Received Power), RSRQ (Quality), and RSSNR (Signal-to-Noise Ratio) to gauge the physical viability of the transmission link.

## 3. Hardware Constraints & Physics
Agents must understand the physical layer limitations when engineering features for Radium.

* **The 140-Byte Limit:** A standard GSM/LTE signaling packet holds exactly 140 bytes. Any payload exceeding this must be manually fragmented (via UDH or Application-Layer Chunking).
* **The Inverse Square Law & PMIC Limits:** At low signals (e.g., -118 dBm), the Power Amplifier (PA) fires at maximum capacity. If the battery is critically low (e.g., <5%), the PMIC (Power Management IC) will throttle the PA to prevent a brownout, resulting in the modem rejecting the PDU transmission with a `RESULT_ERROR_GENERIC_FAILURE` (Denied_1).
* **E.164 Global Routing:** All destination numbers must use the strict E.164 standard (e.g., `+91...`). Local numbers cause SS7 routing failures on the backend when transmitted via raw RIL injection.
* **The Loopback Drop:** Modems actively drop binary SMS packets where the Sender ID matches the Receiver ID (sending to oneself) to prevent signaling loops. Testing requires two distinct MSISDNs.

## 4. Software Stack & API Requirements

### 4.1 Android Permissions & API Levels
* **Required Permissions:** `READ_PHONE_STATE`, `ACCESS_FINE_LOCATION`, `SEND_SMS`, `RECEIVE_SMS`.
* **API 33 (Tiramisu) Receiver Constraints:** BroadcastReceivers registered dynamically in code for implicit intents must specify the export flag (`RECEIVER_EXPORTED`). Failure to do so results in a fatal `SecurityException`.
* **Premium SMS Silent Block:** Android OS heuristically blocks headless SMS transmission. The user must manually grant "Premium SMS Access -> Always Allow" in hidden settings, otherwise the RIL will auto-fail the packet.

### 4.2 The JNI/NDK Bridge
All cryptography and checksum generation occurs in C++ to prevent Java-layer reverse engineering via dex-decompilation.
* The JNI boundary relies on `jbyteArray` for raw byte manipulation, avoiding string encoding corruption inherent in UTF-8/JNI transitions.

## 5. Protocol Specifications

### 5.1 Telephony Callback (Idempotent Registration)
Agents must implement exactly 1:1 mapping of `TelephonyCallback`. Before starting a new probe, the old listener must be unregistered to prevent memory leaks and AP (Application Processor) wake-lock battery drain.

### 5.2 Application-Layer Concatenation (Shattering 140 Bytes)
Because Android's `SmsManager.sendDataMessage()` automatically injects its own Port UDH (User Data Header), manual injection of Concatenation UDH (0x05 0x00 0x03) often corrupts the header.
**Radium Standard:**
* Implement chunking at the Kotlin layer.
* Prepend a 3-byte custom header to every chunk: `[MsgID (1 byte)] [Total Chunks (1 byte)] [Current Chunk (1 byte)]`.
* Max payload per chunk: `140 - 3 = 137 bytes`.
* The receiving `BroadcastReceiver` buffers chunks in memory mapped by `MsgID` until `Current Chunk == Total Chunks`, then passes the contiguous byte array to the NDK for decryption.

### 5.3 Cryptographic Vault (AES-256-GCM)
* **Obsoleted:** Single-byte XOR cipher (v1.0).
* **Current Standard:** AES-256-GCM implemented via Android NDK BoringSSL (`libcrypto`).
* The payload must include a 12-byte IV (Initialization Vector) and a 16-byte Authentication Tag to prevent tampering (bit-flipping attacks common in high-noise radio environments).

## 6. Implementation Directives for AI Agents
When generating or modifying code for Radium:
1.  **Never use standard UI popups for telemetry.** Route all diagnostic data to the `telemetryScroll` console window.
2.  **Always extract hardware parameters** (PCI, EARFCN, RSRP) to verify physical layer integrity before initiating transmission loops.
3.  **Assume hostility from the OS.** Write code that gracefully handles silent drops, PMIC throttling, and unexpected `BroadcastReceiver` lifecycle terminations.
