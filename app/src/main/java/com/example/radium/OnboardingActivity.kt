package com.example.radium

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.radium.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    data class PermSlide(val permission: String, val titleRes: Int, val descRes: Int, val iconRes: Int)

    private val allSlides = listOf(
        PermSlide(Manifest.permission.SEND_SMS, R.string.perm_sms_title, R.string.perm_sms_desc, android.R.drawable.ic_dialog_email),
        PermSlide(Manifest.permission.RECEIVE_SMS, R.string.perm_receive_sms_title, R.string.perm_receive_sms_desc, android.R.drawable.ic_dialog_email),
        PermSlide(Manifest.permission.READ_PHONE_STATE, R.string.perm_phone_title, R.string.perm_phone_desc, android.R.drawable.ic_menu_call),
        PermSlide(Manifest.permission.ACCESS_FINE_LOCATION, R.string.perm_location_title, R.string.perm_location_desc, android.R.drawable.ic_menu_mylocation),
        PermSlide(Manifest.permission.READ_CONTACTS, R.string.perm_contacts_title, R.string.perm_contacts_desc, android.R.drawable.ic_menu_my_calendar),
    )

    private lateinit var missingSlides: List<PermSlide>

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val profile = HardwareScanner.runDiagnostics(this)

        // Skip if all permissions already granted
        missingSlides = allSlides.filter {
            ContextCompat.checkSelfPermission(this, it.permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missingSlides.isEmpty()) {
            launchHome()
            return
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Safe area insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.onboardingRoot) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = sys.top, bottom = sys.bottom)
            insets
        }

        binding.onboardingHardwareSummary.text = profile.onboardingMessage

        binding.onboardingPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = missingSlides.size
            override fun createFragment(pos: Int): Fragment {
                val slide = missingSlides[pos]
                return PermissionSlideFragment.newInstance(slide.permission, slide.titleRes, slide.descRes, slide.iconRes)
            }
        }
        binding.onboardingPager.isUserInputEnabled = false // advance only via button

        buildDots()

        binding.btnGetStarted.setOnClickListener { launchHome() }
    }

    fun onPermissionGranted() {
        val next = binding.onboardingPager.currentItem + 1
        if (next < missingSlides.size) {
            binding.onboardingPager.setCurrentItem(next, true)
            updateDots(next)
        } else {
            binding.btnGetStarted.visibility = View.VISIBLE
            binding.btnGetStarted.alpha = 0f
            binding.btnGetStarted.animate().alpha(1f).setDuration(400).start()
        }
    }

    private fun buildDots() {
        binding.dotContainer.removeAllViews()
        for (i in missingSlides.indices) {
            val dot = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(12, 12).apply { setMargins(6, 0, 6, 0) }
                setBackgroundColor(if (i == 0) getColor(R.color.elysium_primary) else getColor(R.color.tick_grey))
            }
            binding.dotContainer.addView(dot)
        }
    }

    private fun updateDots(pos: Int) {
        for (i in 0 until binding.dotContainer.childCount) {
            binding.dotContainer.getChildAt(i).setBackgroundColor(
                getColor(if (i == pos) R.color.elysium_primary else R.color.tick_grey)
            )
        }
    }

    private fun launchHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
