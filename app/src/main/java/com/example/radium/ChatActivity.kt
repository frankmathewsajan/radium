package com.example.radium

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.*
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.radium.data.ConversationEntity
import com.example.radium.data.MessageEntity
import com.example.radium.data.RadiumDatabase
import com.example.radium.databinding.ActivityChatBinding
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: MessageAdapter

    // Threading: IO for Database, Inference for the heavy LLM math
    private val io: Executor = Executors.newSingleThreadExecutor()
    private val inferenceThread: Executor = Executors.newSingleThreadExecutor()
    private val db by lazy { RadiumDatabase.get(this) }

    // API 31+ strictly enforced Telemetry Listener
    private var telephonyCallback: TelephonyCallback? = null

    private var targetNumber = ""
    private var contactName = ""
    private var lastInsertedId: Long = -1
    private var isThinkingMode = false

    private val aiPrefs by lazy { getSharedPreferences("radium_ai", Context.MODE_PRIVATE) }

    companion object {
        const val GHOST_CONTACT_NPU = "0000000000"
        private const val KEY_THINKING_MODE = "thinking_mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        targetNumber = intent.getStringExtra("number") ?: ""
        contactName = intent.getStringExtra("name") ?: targetNumber

        ViewCompat.setOnApplyWindowInsetsListener(binding.chatRoot) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.chatToolbar.updatePadding(top = sys.top)
            binding.inputBar.updatePadding(bottom = maxOf(sys.bottom, ime.bottom))
            insets
        }

        binding.chatTitle.text = contactName
        binding.chatSubtitle.text = if (targetNumber == GHOST_CONTACT_NPU) "On-Device Neural Engine" else targetNumber
        binding.btnBack.setOnClickListener { finish() }

        isThinkingMode = aiPrefs.getBoolean(KEY_THINKING_MODE, false)
        binding.switchThinkingMode.isChecked = isThinkingMode
        binding.switchThinkingMode.setOnCheckedChangeListener { _, checked ->
            isThinkingMode = checked
            aiPrefs.edit().putBoolean(KEY_THINKING_MODE, checked).apply()
        }

        adapter = MessageAdapter()
        binding.messageList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messageList.adapter = adapter

        binding.btnSend.setOnClickListener { sendMessage() }

        if (targetNumber != GHOST_CONTACT_NPU) {
            binding.switchThinkingMode.visibility = View.GONE
            registerSmsReceivers()
            startTelemetry()
        } else {
            binding.switchThinkingMode.visibility = View.VISIBLE
        }

        loadMessages()
    }

    private fun loadMessages() {
        io.execute {
            val messages = db.messageDao().getMessagesForConversation(targetNumber)
            val items = messages.map { entity ->
                val text = try {
                    RadioEngine.decodeDataNative(entity.encryptedBlob)
                } catch (_: Exception) {
                    "[decrypt error]"
                }
                MessageAdapter.ChatItem(text, entity.isSent, entity.status, entity.timestamp)
            }
            runOnUiThread {
                adapter.submitList(items)
                if (items.isNotEmpty()) binding.messageList.scrollToPosition(items.size - 1)
            }
        }
    }

    private fun sendMessage() {
        val text = binding.messageInput.text.toString().trim()
        if (text.isEmpty() || targetNumber.isEmpty()) return
        binding.messageInput.text.clear()

        // --- THE HYBRID INTERSECTION ---
        if (targetNumber == GHOST_CONTACT_NPU) {
            // Mode 1: Local Beast Compute (No Radio)
            io.execute {
                val promptBlob = RadioEngine.processDataNative(text)
                db.messageDao().insertMessage(MessageEntity(
                    conversationId = targetNumber, encryptedBlob = promptBlob, isSent = true, status = 2
                ))
                db.messageDao().upsertConversation(ConversationEntity(targetNumber, "Llama 3.2 (Local)"))
                loadMessages()

                triggerLocalLlamaInference()
            }
        } else {
            // Mode 2: Signaling Plane Transceiver (Port 8080)
            io.execute {
                val blob = RadioEngine.processDataNative(text)
                val entity = MessageEntity(
                    conversationId = targetNumber, encryptedBlob = blob, isSent = true, status = 0
                )
                lastInsertedId = db.messageDao().insertMessage(entity)
                db.messageDao().upsertConversation(ConversationEntity(targetNumber, contactName))

                val success = RadioEngine.dispatchDataSms(this@ChatActivity, targetNumber, text)
                if (!success) {
                    db.messageDao().updateStatus(lastInsertedId, -1)
                }
                loadMessages()
            }
        }
    }

    // --- NEW: Llama 3 Sliding Window Context Formatter ---
    private fun buildLlamaContextWindow(messages: List<MessageEntity>): String {
        val sb = java.lang.StringBuilder()

        val systemPrompt = if (isThinkingMode) {
            "You are Radium, an advanced tactical AI. Analyze the user's request deeply, reason step-by-step, and provide a comprehensive answer with clear trade-offs."
        } else {
            "You are Radium. Be concise and direct. Answer in 1 to 2 sentences. No fluff."
        }
        sb.append("<|start_header_id|>system<|end_header_id|>\n\n")
            .append(systemPrompt)
            .append("<|eot_id|>")

        // Take the last 6 messages (3 turns) so we don't blow up the 4096 RAM limit
        val contextWindow = messages.takeLast(6)

        for (msg in contextWindow) {
            val text = try { RadioEngine.decodeDataNative(msg.encryptedBlob) } catch (_: Exception) { continue }
            if (text == "Thinking...") continue // Don't feed the temporary UI bubble to the AI

            if (msg.isSent) {
                sb.append("<|start_header_id|>user<|end_header_id|>\n\n").append(text).append("<|eot_id|>")
            } else {
                sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n").append(text).append("<|eot_id|>")
            }
        }

        // Append the final cue to force the AI to start answering the context
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }

    private fun triggerLocalLlamaInference() {
        io.execute {
            // 1. Show the "Thinking..." ghost bubble
            val thinkingBlob = RadioEngine.processDataNative("Thinking...")
            val thinkingEntity = MessageEntity(
                conversationId = targetNumber, encryptedBlob = thinkingBlob, isSent = false, status = 0
            )
            db.messageDao().insertMessage(thinkingEntity)
            loadMessages()

            // 2. Run heavy inference on the dedicated NPU thread
            inferenceThread.execute {
                val aiResponse = if (LocalAiBridge.isModelLoaded) {

                    // Fetch history, build the block, and beam it to C++
                    val history = db.messageDao().getMessagesForConversation(targetNumber)
                    val fullContext = buildLlamaContextWindow(history)
                    LocalAiBridge.generateResponseNative(fullContext)

                } else {
                    "System Error: Neural Engine Offline. Please mount a .gguf model in Settings."
                }

                // 3. Swap the thinking bubble for the real answer
                io.execute {
                    val responseBlob = RadioEngine.processDataNative(aiResponse)
                    db.messageDao().deleteMessage(thinkingEntity)
                    db.messageDao().insertMessage(MessageEntity(
                        conversationId = targetNumber, encryptedBlob = responseBlob, isSent = false, status = 2
                    ))
                    loadMessages()
                }
            }
        }
    }

    // --- SMS lifecycle receivers (Only active for real numbers) ---
    private lateinit var smsResultReceiver: BroadcastReceiver
    private lateinit var incomingDataReceiver: BroadcastReceiver

    private fun registerSmsReceivers() {
        smsResultReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    RadioEngine.ACTION_SMS_SENT -> {
                        val status = if (resultCode == RESULT_OK) 1 else -1
                        if (lastInsertedId > 0) {
                            io.execute { db.messageDao().updateStatus(lastInsertedId, status); loadMessages() }
                        }
                    }
                    RadioEngine.ACTION_SMS_DELIVERED -> {
                        if (lastInsertedId > 0) {
                            io.execute { db.messageDao().updateStatus(lastInsertedId, 2); loadMessages() }
                        }
                    }
                }
            }
        }

        incomingDataReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                val result = RadioEngine.handleIncomingChunk(intent) ?: return
                val (sender, decoded) = result

                io.execute {
                    val reEncrypted = RadioEngine.processDataNative(decoded)
                    db.messageDao().insertMessage(MessageEntity(
                        conversationId = sender, encryptedBlob = reEncrypted, isSent = false, status = 2
                    ))
                    db.messageDao().upsertConversation(ConversationEntity(sender, sender))
                    if (sender == targetNumber) loadMessages()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsResultReceiver, IntentFilter(RadioEngine.ACTION_SMS_SENT), RECEIVER_EXPORTED)
            registerReceiver(smsResultReceiver, IntentFilter(RadioEngine.ACTION_SMS_DELIVERED), RECEIVER_EXPORTED)
            registerReceiver(incomingDataReceiver, IntentFilter("android.intent.action.DATA_SMS_RECEIVED").apply {
                addDataScheme("sms"); addDataAuthority("*", "8080")
            }, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(smsResultReceiver, IntentFilter(RadioEngine.ACTION_SMS_SENT))
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(smsResultReceiver, IntentFilter(RadioEngine.ACTION_SMS_DELIVERED))
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(incomingDataReceiver, IntentFilter("android.intent.action.DATA_SMS_RECEIVED").apply {
                addDataScheme("sms"); addDataAuthority("*", "8080")
            })
        }
    }

    @android.annotation.SuppressLint("SetTextI18n")
    private fun startTelemetry() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        telephonyCallback?.let { tm.unregisterTelephonyCallback(it) }
        val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(ss: SignalStrength) {
                val lte = ss.getCellSignalStrengths(CellSignalStrengthLte::class.java).firstOrNull()
                lte?.let { runOnUiThread { binding.chatSubtitle.text = "$targetNumber · ${it.rsrp}dBm" } }
            }
        }
        tm.registerTelephonyCallback(Executors.newSingleThreadExecutor(), callback)
        telephonyCallback = callback
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (targetNumber != GHOST_CONTACT_NPU) {
                val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                telephonyCallback?.let { tm.unregisterTelephonyCallback(it) }
                unregisterReceiver(smsResultReceiver)
                unregisterReceiver(incomingDataReceiver)
            }
        } catch (_: Exception) {}
    }
}