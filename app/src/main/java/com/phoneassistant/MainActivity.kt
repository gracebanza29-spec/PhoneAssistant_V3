package com.phoneassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.phoneassistant.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val nav = (supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment).navController
        binding.bottomNav.setupWithNavController(nav)

        askPermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "tel") {
            val nav = (supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment).navController
            nav.navigate(R.id.dialpadFragment, Bundle().apply { putString("prefill", data.schemeSpecificPart) })
        }
    }

    private fun askPermissions() {
        val perms = arrayOf(
            Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.SEND_SMS
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray())
    }

    fun call(number: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
        else permLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
    }

    fun sms(number: String) = startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")))
}
