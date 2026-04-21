package com.example.radium

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment

class PermissionSlideFragment : Fragment() {

    private val requestPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Whether granted or denied, advance to next slide
        (activity as? OnboardingActivity)?.onPermissionGranted()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_permission_slide, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val perm = requireArguments().getString(ARG_PERM)!!
        val titleRes = requireArguments().getInt(ARG_TITLE)
        val descRes = requireArguments().getInt(ARG_DESC)
        val iconRes = requireArguments().getInt(ARG_ICON)

        view.findViewById<ImageView>(R.id.slideIcon).setImageResource(iconRes)
        view.findViewById<TextView>(R.id.slideTitle).setText(titleRes)
        view.findViewById<TextView>(R.id.slideDesc).setText(descRes)
        view.findViewById<Button>(R.id.btnAllow).setOnClickListener {
            requestPerm.launch(perm)
        }
    }

    companion object {
        private const val ARG_PERM = "perm"
        private const val ARG_TITLE = "title"
        private const val ARG_DESC = "desc"
        private const val ARG_ICON = "icon"

        fun newInstance(perm: String, titleRes: Int, descRes: Int, iconRes: Int) =
            PermissionSlideFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PERM, perm)
                    putInt(ARG_TITLE, titleRes)
                    putInt(ARG_DESC, descRes)
                    putInt(ARG_ICON, iconRes)
                }
            }
    }
}
