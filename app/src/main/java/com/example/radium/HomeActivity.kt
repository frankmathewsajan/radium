package com.example.radium

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
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

    private val contactPicker = registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        uri ?: return@registerForActivityResult
        val cursor: Cursor? = contentResolver.query(uri, arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        ), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val raw = it.getString(0) ?: return@use
                val name = it.getString(1) ?: raw
                val clean = raw.replace(Regex("[^0-9+]"), "")
                if (clean.isNotEmpty()) openChat(clean, name)
            }
        }
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
        binding.fabNewChat.setOnClickListener { showNewChatDialog() }
    }

    override fun onResume() {
        super.onResume()
        loadConversations()
    }

    private fun loadConversations() {
        io.execute {
            val convs = db.messageDao().getAllConversations()
            runOnUiThread {
                adapter.submitList(convs)
                binding.emptyState.visibility = if (convs.isEmpty()) View.VISIBLE else View.GONE
                binding.conversationList.visibility = if (convs.isEmpty()) View.GONE else View.VISIBLE
            }
        }
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
