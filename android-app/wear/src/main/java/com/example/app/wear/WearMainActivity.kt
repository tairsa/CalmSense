package com.example.app.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class WearMainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.i(TAG, "Permission result: $results")
        if (hasPermission(Manifest.permission.BODY_SENSORS)) {
            HrMonitoringService.start(this)
        } else {
            Log.w(TAG, "BODY_SENSORS not granted; monitoring will not start")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearStatusUi() }

        val needed = buildList {
            if (!hasPermission(Manifest.permission.BODY_SENSORS)) {
                add(Manifest.permission.BODY_SENSORS)
            }
            if (!hasPermission(HEALTH_READ_HEART_RATE)) {
                add(HEALTH_READ_HEART_RATE)
            }
            if (!hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isEmpty()) {
            HrMonitoringService.start(this)
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun hasPermission(name: String): Boolean =
        ContextCompat.checkSelfPermission(this, name) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "WearMainActivity"
        private const val HEALTH_READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
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
