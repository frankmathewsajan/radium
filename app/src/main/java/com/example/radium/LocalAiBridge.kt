package com.example.radium

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

object LocalAiBridge {

    private const val PREFS_NAME = "radium_ai"
    private const val KEY_ACTIVE_MODEL = "active_model_file"
    private const val MIN_MODEL_BYTES = 1_000_000L

    private var loadedModelPath: String? = null

    // The JNI Hooks to your C++ Metal
    external fun loadModelNative(modelPath: String): Boolean
    external fun generateResponseNative(prompt: String): String
    external fun unloadModelNative()

    var isModelLoaded = false
        private set

    /**
     * Secures the model by copying it from the user's Downloads into the App Sandbox.
     * This bypasses Android's Scoped Storage so the C++ engine can read it directly.
     */
    fun secureModelToSandbox(context: Context, sourceUri: Uri, preferredFileName: String? = null): String {
        val fileName = preferredFileName ?: resolveFileName(context, sourceUri)
        val sandboxFile = File(context.filesDir, fileName)

        // If it's already copied, just return the safe path
        if (sandboxFile.exists() && sandboxFile.length() > MIN_MODEL_BYTES) {
            setActiveModelFile(context, sandboxFile.name)
            return sandboxFile.absolutePath
        }

        // Otherwise, stream the 2GB file into the vault
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(sandboxFile).use { output ->
                input.copyTo(output)
            }
        }
        setActiveModelFile(context, sandboxFile.name)
        return sandboxFile.absolutePath
    }

    fun setActiveModelFile(context: Context, fileName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_MODEL, fileName)
            .apply()
    }

    fun getActiveModelFile(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_MODEL, null)
    }

    fun getActiveModelPath(context: Context): String? {
        val fileName = getActiveModelFile(context) ?: return null
        val file = File(context.filesDir, fileName)
        return if (file.exists() && file.length() > MIN_MODEL_BYTES) file.absolutePath else null
    }

    @Synchronized
    fun switchModel(context: Context, fileName: String): Boolean {
        val target = File(context.filesDir, fileName)
        if (!target.exists() || target.length() <= MIN_MODEL_BYTES) {
            return false
        }
        unloadNeuralEngine()
        val loaded = bootNeuralEngine(target.absolutePath)
        if (loaded) {
            setActiveModelFile(context, fileName)
        }
        return loaded
    }

    /** Loads the model into physical RAM via the NPU/CPU */
    @Synchronized
    fun bootNeuralEngine(modelPath: String): Boolean {
        val resolvedPath = File(modelPath).absolutePath
        if (isModelLoaded && loadedModelPath == resolvedPath) return true

        if (isModelLoaded) {
            unloadModelNative()
            isModelLoaded = false
            loadedModelPath = null
        }

        isModelLoaded = loadModelNative(resolvedPath)
        loadedModelPath = if (isModelLoaded) resolvedPath else null
        return isModelLoaded
    }

    @Synchronized
    fun unloadNeuralEngine() {
        if (isModelLoaded) {
            unloadModelNative()
            isModelLoaded = false
            loadedModelPath = null
        }
    }

    private fun resolveFileName(context: Context, sourceUri: Uri): String {
        val displayName = context.contentResolver.query(
            sourceUri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else {
                null
            }
        }

        val rawName = displayName ?: sourceUri.lastPathSegment ?: "model.gguf"
        return rawName
            .substringAfterLast('/')
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .let { if (it.endsWith(".gguf", ignoreCase = true)) it else "$it.gguf" }
    }
}