package moe.shizuku.manager.home

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R

import android.content.Intent
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.starter.StarterActivity

class WadbNotEnabledDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        return MaterialAlertDialogBuilder(context)
            .setMessage(R.string.dialog_wireless_adb_not_enabled)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.home_quick_tcp) { _, _ ->
                val intent = Intent(context, StarterActivity::class.java).apply {
                    putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                    putExtra(StarterActivity.EXTRA_HOST, "127.0.0.1")
                    putExtra(StarterActivity.EXTRA_PORT, AdbStarter.TCP_MODE_PORT)
                }
                context.startActivity(intent)
            }
            .create()
    }

    fun show(fragmentManager: FragmentManager) {
        if (fragmentManager.isStateSaved) return
        show(fragmentManager, javaClass.simpleName)
    }
}
