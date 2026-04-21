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
    private val io: Executor = Executors.newSingleThreadExecutor()
    private val db by lazy { RadiumDatabase.get(this) }
    private var activeCallback: TelephonyCallback? = null
    private var targetNumber = ""
    private var contactName = ""
    private var lastInsertedId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        targetNumber = intent.getStringExtra("number") ?: ""
        contactName = intent.getStringExtra("name") ?: targetNumber

        // Safe area: toolbar top + input bar bottom (IME-aware)
        ViewCompat.setOnApplyWindowInsetsListener(binding.chatRoot) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.chatToolbar.updatePadding(top = sys.top)
            binding.inputBar.updatePadding(bottom = maxOf(sys.bottom, ime.bottom))
            insets
        }

        binding.chatTitle.text = contactName
        binding.chatSubtitle.text = targetNumber
        binding.btnBack.setOnClickListener { finish() }

        adapter = MessageAdapter()
        binding.messageList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messageList.adapter = adapter

        binding.btnSend.setOnClickListener { sendMessage() }

        registerSmsReceivers()
        startTelemetry()
        loadMessages()
    }

    private fun loadMessages() {
        io.execute {
            val msgs = db.messageDao().getMessagesForConversation(targetNumber)
            val items = msgs.map { entity ->
                val text = if (entity.isSent) {
                    try { RadioEngine.decodeDataNative(entity.encryptedBlob) } catch (_: Exception) { "[decrypt error]" }
                } else {
                    try { RadioEngine.decodeDataNative(entity.encryptedBlob) } catch (_: Exception) { "[decrypt error]" }
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

        io.execute {
            // Encrypt for storage
            val blob = RadioEngine.processDataNative(text)
            val entity = MessageEntity(
                conversationId = targetNumber,
                encryptedBlob = blob,
                isSent = true,
                status = 0
            )
            lastInsertedId = db.messageDao().insertMessage(entity)
            db.messageDao().upsertConversation(ConversationEntity(targetNumber, contactName))

            // Dispatch over radio
            val success = RadioEngine.dispatchDataSms(this, targetNumber, text)
            if (!success) {
                db.messageDao().updateStatus(lastInsertedId, -1)
            }
            loadMessages()
        }
    }

    // --- SMS lifecycle receivers ---
    private lateinit var smsResultReceiver: BroadcastReceiver
    private lateinit var incomingDataReceiver: BroadcastReceiver

    private fun registerSmsReceivers() {
        smsResultReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    RadioEngine.ACTION_SMS_SENT -> {
                        val status = if (resultCode == RESULT_OK) 1 else -1
                        if (lastInsertedId > 0) {
                            io.execute {
                                db.messageDao().updateStatus(lastInsertedId, status)
                                loadMessages()
                            }
                        }
                    }
                    RadioEngine.ACTION_SMS_DELIVERED -> {
                        if (lastInsertedId > 0) {
                            io.execute {
                                db.messageDao().updateStatus(lastInsertedId, 2)
                                loadMessages()
                            }
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
                    // Re-encrypt for secure storage
                    val reEncrypted = RadioEngine.processDataNative(decoded)
                    db.messageDao().insertMessage(MessageEntity(
                        conversationId = sender,
                        encryptedBlob = reEncrypted,
                        isSent = false,
                        status = 2
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

    private fun startTelemetry() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        activeCallback?.let { tm.unregisterTelephonyCallback(it) }
        val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(ss: SignalStrength) {
                val lte = ss.getCellSignalStrengths(CellSignalStrengthLte::class.java).firstOrNull()
                lte?.let { runOnUiThread { binding.chatSubtitle.text = "$targetNumber · ${it.rsrp}dBm" } }
            }
        }
        tm.registerTelephonyCallback(Executors.newSingleThreadExecutor(), callback)
        activeCallback = callback

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            tm.allCellInfo.filterIsInstance<CellInfoLte>().firstOrNull { it.isRegistered }?.let {
                binding.chatSubtitle.text = "$targetNumber · PCI:${it.cellIdentity.pci}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            activeCallback?.let { tm.unregisterTelephonyCallback(it) }
            unregisterReceiver(smsResultReceiver)
            unregisterReceiver(incomingDataReceiver)
        } catch (_: Exception) {}
    }
}
