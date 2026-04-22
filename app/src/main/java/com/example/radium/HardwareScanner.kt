package com.example.radium

import android.app.ActivityManager
import android.content.Context
import kotlin.math.roundToInt

object HardwareScanner {

    data class ModelOption(
        val displayName: String,
        val modelUrl: String,
        val downloadUrl: String,
        val fileName: String,
        val maxContextWindow: Int
    )

    data class DeviceProfile(
        val totalRamGb: Int,
        val recommendedModel: String,
        val modelUrl: String,
        val downloadUrl: String,
        val fileName: String,
        val maxContextWindow: Int,
        val options: List<ModelOption>,
        val onboardingMessage: String
    )

    fun runDiagnostics(context: Context): DeviceProfile {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)

        // Convert bytes to GB and round for user-facing diagnostics.
        val actualRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val advertisedRam = actualRamGb.roundToInt() + 1

        val options = when {
            advertisedRam >= 12 -> listOf(
                ModelOption(
                    displayName = "Llama 3.1 8B Instruct (Q4_K_M)",
                    modelUrl = "bartowski/Meta-Llama-3.1-8B-Instruct-GGUF",
                    downloadUrl = "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
                    fileName = "llama-3.1-8b-q4_k_m.gguf",
                    maxContextWindow = 8192
                ),
                ModelOption(
                    displayName = "Qwen 2.5 7B Instruct (Q4_K_M)",
                    modelUrl = "bartowski/Qwen2.5-7B-Instruct-GGUF",
                    downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
                    fileName = "qwen2.5-7b-q4_k_m.gguf",
                    maxContextWindow = 8192
                ),
                ModelOption(
                    displayName = "Qwen 2.5 3B Instruct (Q4_K_M)",
                    modelUrl = "bartowski/Qwen2.5-3B-Instruct-GGUF",
                    downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
                    fileName = "qwen2.5-3b-q4_k_m.gguf",
                    maxContextWindow = 6144
                ),
                ModelOption(
                    displayName = "Llama 3.2 3B Instruct (Q4_K_M)",
                    modelUrl = "bartowski/Llama-3.2-3B-Instruct-GGUF",
                    downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                    fileName = "llama-3.2-3b-q4_k_m.gguf",
                    maxContextWindow = 4096
                )
            )

            advertisedRam >= 8 -> listOf(
                ModelOption(
                    displayName = "Llama 3.2 3B Instruct (Q4_K_M)",
                    modelUrl = "bartowski/Llama-3.2-3B-Instruct-GGUF",
                    downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                    fileName = "llama-3.2-3b-q4_k_m.gguf",
                    maxContextWindow = 4096
                ),
                ModelOption(
                    displayName = "Qwen 2.5 3B Instruct (Q4_K_M)",
                    modelUrl = "bartowski/Qwen2.5-3B-Instruct-GGUF",
                    downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
                    fileName = "qwen2.5-3b-q4_k_m.gguf",
                    maxContextWindow = 4096
                ),
                ModelOption(
                    displayName = "Qwen 2.5 1.5B Instruct (Q5_K_M)",
                    modelUrl = "bartowski/Qwen2.5-1.5B-Instruct-GGUF",
                    downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q5_K_M.gguf",
                    fileName = "qwen2.5-1.5b-q5_k_m.gguf",
                    maxContextWindow = 3072
                ),
                ModelOption(
                    displayName = "Llama 3.2 1B Instruct (Q5_K_M)",
                    modelUrl = "bartowski/Llama-3.2-1B-Instruct-GGUF",
                    downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q5_K_M.gguf",
                    fileName = "llama-3.2-1b-q5_k_m.gguf",
                    maxContextWindow = 2048
                )
            )

            advertisedRam >= 6 -> listOf(
                ModelOption(
                    displayName = "Llama 3.2 1B Instruct (Q5_K_M)",
                    modelUrl = "bartowski/Llama-3.2-1B-Instruct-GGUF",
                    downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q5_K_M.gguf",
                    fileName = "llama-3.2-1b-q5_k_m.gguf",
                    maxContextWindow = 2048
                ),
                ModelOption(
                    displayName = "Qwen 2.5 1.5B Instruct (Q5_K_M)",
                    modelUrl = "bartowski/Qwen2.5-1.5B-Instruct-GGUF",
                    downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q5_K_M.gguf",
                    fileName = "qwen2.5-1.5b-q5_k_m.gguf",
                    maxContextWindow = 2048
                ),
                ModelOption(
                    displayName = "TinyLlama 1.1B Chat (Q5_K_M)",
                    modelUrl = "TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF",
                    downloadUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q5_K_M.gguf",
                    fileName = "tinyllama-1.1b-q5_k_m.gguf",
                    maxContextWindow = 2048
                ),
                ModelOption(
                    displayName = "Qwen 2.5 0.5B Instruct (Q4_0)",
                    modelUrl = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
                    downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf",
                    fileName = "qwen2.5-0.5b-q4_0.gguf",
                    maxContextWindow = 1536
                )
            )

            advertisedRam >= 4 -> listOf(
                ModelOption(
                    displayName = "Qwen 2.5 0.5B Instruct (Q4_0)",
                    modelUrl = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
                    downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf",
                    fileName = "qwen2.5-0.5b-q4_0.gguf",
                    maxContextWindow = 1024
                ),
                ModelOption(
                    displayName = "TinyLlama 1.1B Chat (Q4_K_M)",
                    modelUrl = "TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF",
                    downloadUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
                    fileName = "tinyllama-1.1b-q4_k_m.gguf",
                    maxContextWindow = 1024
                ),
                ModelOption(
                    displayName = "SmolLM2 360M Instruct (Q8_0)",
                    modelUrl = "HuggingFaceTB/SmolLM2-360M-Instruct-GGUF",
                    downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q8_0.gguf",
                    fileName = "smollm2-360m-q8_0.gguf",
                    maxContextWindow = 1024
                )
            )

            else -> listOf(
                ModelOption(
                    displayName = "Qwen 2.5 0.5B Instruct (Q4_0)",
                    modelUrl = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
                    downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf",
                    fileName = "qwen2.5-0.5b-q4_0.gguf",
                    maxContextWindow = 1024
                ),
                ModelOption(
                    displayName = "SmolLM2 360M Instruct (Q8_0)",
                    modelUrl = "HuggingFaceTB/SmolLM2-360M-Instruct-GGUF",
                    downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q8_0.gguf",
                    fileName = "smollm2-360m-q8_0.gguf",
                    maxContextWindow = 768
                )
            )
        }

        val recommended = options.first()
        return DeviceProfile(
            totalRamGb = advertisedRam,
            recommendedModel = recommended.displayName,
            modelUrl = recommended.modelUrl,
            downloadUrl = recommended.downloadUrl,
            fileName = recommended.fileName,
            maxContextWindow = recommended.maxContextWindow,
            options = options,
            onboardingMessage = "We detected ${advertisedRam}GB of RAM. Recommended: ${recommended.displayName}. " +
                "Fast mode is ideal for quick replies, and Deep mode is best for long reasoning."
        )
    }
}
