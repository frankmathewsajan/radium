package com.example.radium

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.telephony.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.radium.databinding.ActivityMainBinding
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val executor: Executor = Executors.newSingleThreadExecutor()
    private var activeCallback: TelephonyCallback? = null

    // Buffer to hold fragmented incoming packets before AES decryption
    private val messageBuffer = mutableMapOf<Byte, MutableList<ByteArray>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        log("NDK: ${stringFromJNI()}")

        binding.probeButton.setOnClickListener { checkPermissionsAndProbe() }
        binding.smsButton.setOnClickListener { dispatchDataSms() }

        // 1. Register SMS Lifecycle Listeners (Sent/Delivered)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsResultReceiver, IntentFilter(ACTION_SMS_SENT), RECEIVER_EXPORTED)
            registerReceiver(smsResultReceiver, IntentFilter(ACTION_SMS_DELIVERED), RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(smsResultReceiver, IntentFilter(ACTION_SMS_SENT))
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(smsResultReceiver, IntentFilter(ACTION_SMS_DELIVERED))
        }

        // 2. Register Binary SMS Interceptor (Port 8080)
        val dataFilter = IntentFilter("android.intent.action.DATA_SMS_RECEIVED").apply {
            addDataScheme("sms")
            addDataAuthority("*", "8080")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(incomingDataReceiver, dataFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(incomingDataReceiver, dataFilter)
        }
    }

    private fun checkPermissionsAndProbe() {
        val perms = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS
        )
        if (perms.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            executeTelemetry()
        } else {
            ActivityCompat.requestPermissions(this, perms, 101)
        }
    }

    private fun executeTelemetry() {
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activeCallback?.let { tm.unregisterTelephonyCallback(it) }
            val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                override fun onSignalStrengthsChanged(ss: SignalStrength) {
                    val lte = ss.getCellSignalStrengths(CellSignalStrengthLte::class.java).firstOrNull()
                    lte?.let {
                        log("${it.rsrp} | ${it.rsrq} | SNR:${it.rssnr}")
                    }
                }
            }
            tm.registerTelephonyCallback(executor, callback)
            activeCallback = callback
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            tm.allCellInfo.filterIsInstance<CellInfoLte>().firstOrNull { it.isRegistered }?.let {
                val id = it.cellIdentity
                val statusText = "PCI: ${id.pci} | EARFCN: ${id.earfcn}"
                runOnUiThread { binding.networkStatusHeader.text = statusText }
                log("TOWER: ${id.pci} | ${id.ci}")
            }
        }
    }

    private fun dispatchDataSms() {
        val targetNumber = binding.targetNumberInput.text.toString().trim()
        val customMessage = binding.payloadInput.text.toString()

        if (targetNumber.isEmpty()) {
            log("[ERR] Target number missing.")
            return
        }
        if (!targetNumber.startsWith("+")) {
            log("[WARN] E.164 Country Code missing. Routing may drop.")
        }
        if (customMessage.isEmpty()) {
            log("[ERR] Cannot dispatch an empty payload.")
            return
        }

        try {
            val smsManager = getSystemService(SmsManager::class.java)

            // 1. Hand off to C++ to get the AES-256 encrypted blob
            val fullPayload = processDataNative(customMessage)

            // 2. Fragmentation Logic (Shattering the 140-byte limit)
            val chunkSize = 130
            val totalParts = Math.ceil(fullPayload.size.toDouble() / chunkSize).toInt()
            val msgId = (0..255).random().toByte()

            log("[TX] Encrypted blob: ${fullPayload.size} bytes. Shattering into $totalParts chunks...")

            for (i in 0 until totalParts) {
                val start = i * chunkSize
                val end = Math.min(start + chunkSize, fullPayload.size)
                val chunkData = fullPayload.copyOfRange(start, end)

                // 3. Prepend Custom Header: [MsgID] [TotalParts] [CurrentPart]
                val packet = ByteArray(3 + chunkData.size)
                packet[0] = msgId
                packet[1] = totalParts.toByte()
                packet[2] = (i + 1).toByte() // 1-indexed part number
                System.arraycopy(chunkData, 0, packet, 3, chunkData.size)

                // We use 'i' as the request code to keep the PendingIntents distinct for each chunk
                val sentPI = PendingIntent.getBroadcast(this, i, Intent(ACTION_SMS_SENT), PendingIntent.FLAG_IMMUTABLE)
                val deliveredPI = PendingIntent.getBroadcast(this, i, Intent(ACTION_SMS_DELIVERED), PendingIntent.FLAG_IMMUTABLE)

                smsManager.sendDataMessage(targetNumber, null, 8080.toShort(), packet, sentPI, deliveredPI)

                // Throttle injection to prevent RIL buffer overflow
                Thread.sleep(150)
            }
            log("TX: All chunks handed to RIL. Awaiting Acks...")
        } catch (e: Exception) {
            log("FAIL: ${e.message}")
        }
    }

    // --- RECEIVERS ---

    private val smsResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SMS_SENT -> {
                    val status = if (resultCode == RESULT_OK) "Accepted" else "Denied_$resultCode"
                    log("NET: $status")
                }
                ACTION_SMS_DELIVERED -> log("NET: Delivered")
            }
        }
    }

    private val incomingDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.intent.action.DATA_SMS_RECEIVED") {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                for (sms in messages) {
                    val rawData = sms.userData

                    // Verify this is a valid Radium chunk (at least 3 bytes for header)
                    if (rawData != null && rawData.size >= 3) {

                        val msgId = rawData[0]
                        val totalParts = rawData[1].toInt()
                        val currentPart = rawData[2].toInt()

                        log("[RX] Caught Chunk $currentPart/$totalParts for MsgID $msgId")

                        val chunkSlice = rawData.copyOfRange(3, rawData.size)

                        // 1. Initialize buffer for this specific MsgID if it doesn't exist
                        if (!messageBuffer.containsKey(msgId)) {
                            messageBuffer[msgId] = MutableList(totalParts) { ByteArray(0) }
                        }

                        // 2. Store the chunk in its correct order slot
                        messageBuffer[msgId]!![currentPart - 1] = chunkSlice

                        // 3. Check if all parts have successfully arrived
                        var isComplete = true
                        var totalSize = 0
                        for (part in messageBuffer[msgId]!!) {
                            if (part.isEmpty()) isComplete = false
                            else totalSize += part.size
                        }

                        // 4. Reassembly & Decryption
                        if (isComplete) {
                            log("[SYS] All chunks present. Reassembling $totalSize bytes...")
                            val fullEncryptedBlob = ByteArray(totalSize)
                            var cursor = 0
                            for (part in messageBuffer[msgId]!!) {
                                System.arraycopy(part, 0, fullEncryptedBlob, cursor, part.size)
                                cursor += part.size
                            }

                            // Hand the completed blob back to C++ to break the AES cipher
                            val decodedText = decodeDataNative(fullEncryptedBlob)
                            log(decodedText)

                            // Clear memory
                            messageBuffer.remove(msgId)
                        }
                    }
                }
            }
        }
    }

    // --- UTILS ---

    private fun log(msg: String) {
        runOnUiThread {
            binding.telemetryDisplay.append("\n$msg")
            binding.telemetryScroll.post { binding.telemetryScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activeCallback?.let { tm.unregisterTelephonyCallback(it) }
        }
        unregisterReceiver(smsResultReceiver)
        unregisterReceiver(incomingDataReceiver)
    }

    // --- NDK BRIDGES ---
    external fun stringFromJNI(): String
    external fun processDataNative(input: String): ByteArray
    external fun decodeDataNative(input: ByteArray): String

    companion object {
        private const val ACTION_SMS_SENT = "com.example.radium.SMS_SENT"
        private const val ACTION_SMS_DELIVERED = "com.example.radium.SMS_DELIVERED"
        init { System.loadLibrary("radium") }
    }
}