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
import java.io.File
import java.util.concurrent.Executors

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val db by lazy { RadiumDatabase.get(this) }
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var adapter: ConversationAdapter

    companion object {
        const val DEFAULT_SERVER = "+919497182886"
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
        mountActiveModelIfPresent()

        binding.chipDefaultServer.setOnClickListener { openChat(DEFAULT_SERVER, "Default Server") }

        binding.chipMountNpu.setOnClickListener { showModelVaultDialog() }

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

    private fun mountActiveModelIfPresent() {
        io.execute {
            val modelPath = LocalAiBridge.getActiveModelPath(this@HomeActivity) ?: return@execute
            LocalAiBridge.bootNeuralEngine(modelPath)
        }
    }

    private fun showModelVaultDialog() {
        val profile = HardwareScanner.runDiagnostics(this)
        val options = profile.options
        var selectedIndex = 0
        val labels = options.mapIndexed { index, option ->
            val tierTag = if (index == 0) "Recommended" else "Alternative"
            "$tierTag: ${option.displayName} (${option.maxContextWindow} ctx)"
        }.toTypedArray()

        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle("Hardware Profiler")
            .setMessage(profile.onboardingMessage)
            .setSingleChoiceItems(labels, selectedIndex) { _, which -> selectedIndex = which }
            .setPositiveButton("Download OTA") { _, _ ->
                downloadAndBootModel(options[selectedIndex])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadAndBootModel(option: HardwareScanner.ModelOption) {
        binding.downloadContainer.visibility = View.VISIBLE
        binding.chipMountNpu.isEnabled = false
        binding.downloadProgressBar.progress = 0
        binding.downloadStatusText.text = "Preparing ${option.displayName}..."

        val destFile = File(filesDir, option.fileName)
        io.execute {
            ModelDownloader.downloadModelOta(
                targetUrl = option.downloadUrl,
                destFile = destFile,
                onProgress = { percent, text ->
                    runOnUiThread {
                        binding.downloadProgressBar.progress = percent
                        binding.downloadStatusText.text = "Downloading ${option.displayName}: $text"
                    }
                },
                onSuccess = {
                    val isAwake = LocalAiBridge.switchModel(this@HomeActivity, option.fileName)
                    runOnUiThread {
                        binding.downloadContainer.visibility = View.GONE
                        binding.chipMountNpu.isEnabled = true
                        if (isAwake) {
                            Toast.makeText(this@HomeActivity, "NPU Online. Active model: ${option.displayName}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@HomeActivity, "Model boot failed.", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onError = { errorMsg ->
                    runOnUiThread {
                        binding.downloadContainer.visibility = View.GONE
                        binding.chipMountNpu.isEnabled = true
                        Toast.makeText(this@HomeActivity, "Download failed: $errorMsg", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }
}
