package com.example.radium

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.radium.data.ConversationEntity
import com.example.radium.data.RadiumDatabase
import com.example.radium.databinding.ActivityHomeBinding
import com.example.radium.databinding.DialogNewChatBinding
import java.util.concurrent.Executors

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val db by lazy { RadiumDatabase.get(this) }
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var adapter: ConversationAdapter

    companion object {
        const val DEFAULT_SERVER = "+919497182886"
    }

    // --- NEW: The Vault File Picker ---
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult

        Toast.makeText(this, "Mounting Neural Engine... This may take a minute.", Toast.LENGTH_LONG).show()

        io.execute {
            try {
                // 1. Stream the 2GB file from Downloads into the App's secure Vault
                val safePath = LocalAiBridge.secureModelToSandbox(this@HomeActivity, uri, "llama-3.2-3b-q4.gguf")

                // 2. Boot the C++ Metal Engine
                val isAwake = LocalAiBridge.bootNeuralEngine(safePath)

                runOnUiThread {
                    if (isAwake) {
                        Toast.makeText(this@HomeActivity, "NPU Online. The Ghost is awake.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@HomeActivity, "Failed to boot Neural Engine. Check Logcat.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@HomeActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private val contactPicker = registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            // Step 1: Get the contact ID from the returned URI
            val contactId = contentResolver.query(
                uri, arrayOf(ContactsContract.Contacts._ID), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

            if (contactId != null) {
                // Step 2: Query phone numbers for this contact ID
                contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    ),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId), null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val raw = c.getString(0) ?: return@use
                        val name = c.getString(1) ?: raw
                        val clean = raw.replace(Regex("[^0-9+]"), "")
                        if (clean.isNotEmpty()) openChat(clean, name)
                    }
                }
            }
        } catch (_: Exception) { /* Silently handle malformed contact data */ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.homeRoot) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.findViewById<View>(R.id.homeHeader).updatePadding(top = sys.top)
            v.findViewById<View>(R.id.fabNewChat).let {
                val lp = it.layoutParams as android.widget.FrameLayout.LayoutParams
                lp.bottomMargin = sys.bottom + 20
                it.layoutParams = lp
            }
            insets
        }

        adapter = ConversationAdapter { conv -> openChat(conv.phoneNumber, conv.contactName) }
        binding.conversationList.layoutManager = LinearLayoutManager(this)
        binding.conversationList.adapter = adapter

        binding.chipDefaultServer.setOnClickListener { openChat(DEFAULT_SERVER, "Default Server") }

        // --- NEW: Trigger the File Picker ---
        binding.chipMountNpu.setOnClickListener {
            modelPicker.launch(arrayOf("application/octet-stream", "*/*"))
        }

        binding.fabNewChat.setOnClickListener { showNewChatDialog() }
    }

    override fun onResume() {
        super.onResume()
        loadConversations()
    }

    private fun loadConversations() {
        io.execute {
            val convs = db.messageDao().getAllConversations().toMutableList()
            // Resolve missing contact names from the phone book
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                for (i in convs.indices) {
                    val c = convs[i]
                    if (c.contactName.isEmpty() || c.contactName == c.phoneNumber) {
                        val resolved = resolveContactName(c.phoneNumber)
                        if (resolved != null && resolved != c.phoneNumber) {
                            val updated = c.copy(contactName = resolved)
                            convs[i] = updated
                            db.messageDao().upsertConversation(updated)
                        }
                    }
                }
            }
            runOnUiThread {
                adapter.submitList(convs)
                binding.emptyState.visibility = if (convs.isEmpty()) View.VISIBLE else View.GONE
                binding.conversationList.visibility = if (convs.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun resolveContactName(phone: String): String? {
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(phone)
            )
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) { null }
    }

    private fun showNewChatDialog() {
        val dialogBinding = DialogNewChatBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.btnContacts.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                contactPicker.launch(null)
                dialog.dismiss()
            }
        }
        dialogBinding.btnStartChat.setOnClickListener {
            val raw = dialogBinding.inputNumber.text.toString().trim()
            val number = raw.replace(Regex("[^0-9+]"), "")
            if (number.isNotEmpty()) {
                openChat(number, number)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun openChat(number: String, name: String) {
        io.execute {
            db.messageDao().upsertConversation(ConversationEntity(number, name))
        }
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra("number", number)
            putExtra("name", name)
        })
    }
}