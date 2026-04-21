package com.example.radium

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import android.provider.Telephony
import android.telephony.SmsMessage

/**
 * Singleton bridge to libradium NDK and the SMS radio layer.
 * All crypto passes through JNI — never touches Java-layer plaintext storage.
 */
object RadioEngine {

    fun interface StatusCallback {
        fun onStatus(msg: String)
    }

    private val messageBuffer = mutableMapOf<String, MutableList<ByteArray>>()
    private var callback: StatusCallback? = null

    init { System.loadLibrary("radium") }

    external fun stringFromJNI(): String
    external fun processDataNative(input: String): ByteArray
    external fun decodeDataNative(input: ByteArray): String

    fun setCallback(cb: StatusCallback?) { callback = cb }

    /** Encrypt, fragment, and dispatch via the signaling plane. */
    fun dispatchDataSms(ctx: Context, targetNumber: String, message: String,
                        onChunkSent: ((Int, Int) -> Unit)? = null): Boolean {
        if (targetNumber.isEmpty() || message.isEmpty()) return false
        if (!targetNumber.startsWith("+")) {
            callback?.onStatus("[WARN] E.164 Country Code missing.")
        }
        return try {
            val smsManager = ctx.getSystemService(SmsManager::class.java)
            val fullPayload = processDataNative(message)
            val chunkSize = 130
            val totalParts = (fullPayload.size + chunkSize - 1) / chunkSize
            val msgId = (0..255).random().toByte()

            callback?.onStatus("[TX] ${fullPayload.size}B → $totalParts chunks")

            for (i in 0 until totalParts) {
                val start = i * chunkSize
                val end = minOf(start + chunkSize, fullPayload.size)
                val chunkData = fullPayload.copyOfRange(start, end)

                val packet = ByteArray(3 + chunkData.size)
                packet[0] = msgId
                packet[1] = totalParts.toByte()
                packet[2] = (i + 1).toByte()
                System.arraycopy(chunkData, 0, packet, 3, chunkData.size)

                val sentPI = PendingIntent.getBroadcast(ctx, i, Intent(ACTION_SMS_SENT), PendingIntent.FLAG_IMMUTABLE)
                val deliveredPI = PendingIntent.getBroadcast(ctx, i, Intent(ACTION_SMS_DELIVERED), PendingIntent.FLAG_IMMUTABLE)

                smsManager.sendDataMessage(targetNumber, null, 8080.toShort(), packet, sentPI, deliveredPI)
                onChunkSent?.invoke(i + 1, totalParts)
                Thread.sleep(150)
            }
            true
        } catch (e: Exception) {
            callback?.onStatus("[FAIL] ${e.message}")
            false
        }
    }

    /** Process an incoming binary SMS chunk; returns decoded text when complete, null otherwise. */
    fun handleIncomingChunk(intent: Intent): Pair<String, String>? {
        val messages: Array<SmsMessage> = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return null
        for (sms in messages) {
            val rawData = sms.userData ?: continue
            if (rawData.size < 3) continue

            val sender = sms.originatingAddress ?: "unknown"
            val msgId = rawData[0]
            val totalParts = rawData[1].toInt()
            val currentPart = rawData[2].toInt()
            val bufferKey = "${sender}_${msgId}"

            callback?.onStatus("[RX] Chunk $currentPart/$totalParts from $sender")

            val chunkSlice = rawData.copyOfRange(3, rawData.size)

            if (!messageBuffer.containsKey(bufferKey)) {
                messageBuffer[bufferKey] = MutableList(totalParts) { ByteArray(0) }
            }
            messageBuffer[bufferKey]!![currentPart - 1] = chunkSlice

            var complete = true
            var totalSize = 0
            for (part in messageBuffer[bufferKey]!!) {
                if (part.isEmpty()) complete = false
                else totalSize += part.size
            }

            if (complete) {
                val blob = ByteArray(totalSize)
                var cursor = 0
                for (part in messageBuffer[bufferKey]!!) {
                    System.arraycopy(part, 0, blob, cursor, part.size)
                    cursor += part.size
                }
                messageBuffer.remove(bufferKey)
                return sender to decodeDataNative(blob)
            }
        }
        return null
    }

    /** Register lifecycle receivers on an Activity. Call unregister in onDestroy. */
    fun registerReceivers(activity: Activity, onSent: () -> Unit, onDelivered: () -> Unit, onFailed: () -> Unit) {
        val sentReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_SMS_SENT -> {
                        if (resultCode == Activity.RESULT_OK) onSent() else onFailed()
                    }
                    ACTION_SMS_DELIVERED -> onDelivered()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(sentReceiver, IntentFilter(ACTION_SMS_SENT), Context.RECEIVER_EXPORTED)
            activity.registerReceiver(sentReceiver, IntentFilter(ACTION_SMS_DELIVERED), Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(sentReceiver, IntentFilter(ACTION_SMS_SENT))
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(sentReceiver, IntentFilter(ACTION_SMS_DELIVERED))
        }
    }

    const val ACTION_SMS_SENT = "com.example.radium.SMS_SENT"
    const val ACTION_SMS_DELIVERED = "com.example.radium.SMS_DELIVERED"
}
