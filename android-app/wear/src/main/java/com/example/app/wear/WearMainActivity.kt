package com.example.app.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class WearMainActivity : ComponentActivity() {

    private val sensorPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) registerPassiveListener()
        else Log.w(TAG, "Sensor permissions not all granted: $results")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearStatusUi() }

        val needed = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            registerPassiveListener()
        } else {
            sensorPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun registerPassiveListener() {
        lifecycleScope.launch {
            runCatching {
                val client = HealthServices.getClient(this@WearMainActivity).passiveMonitoringClient
                val config = PassiveListenerConfig.builder()
                    .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                    .build()
                client.setPassiveListenerServiceAsync(
                    HrPassiveListenerService::class.java,
                    config
                ).await()
                Log.i(TAG, "Passive HR listener registered")
            }.onFailure {
                Log.e(TAG, "Failed to register passive listener", it)
            }
        }
    }

    companion object {
        private const val TAG = "WearMainActivity"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BODY_SENSORS,
            "android.permission.health.READ_HEART_RATE",
        )
    }
}

@Composable
fun WearStatusUi() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "CalmSense",
                color = Color(0xFF8FB3C8),
                fontSize = 18.sp,
                fontWeight = FontWeight.Light,
            )
            Text(
                text = "Monitoring HR",
                color = Color(0xFFA5C49A),
                fontSize = 14.sp,
            )
        }
    }
}
