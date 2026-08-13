package com.jeffery.cellularmonitor

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jeffery.cellularmonitor.ui.MonitorScreen
import com.jeffery.cellularmonitor.ui.rememberMonitorController
import com.jeffery.cellularmonitor.ui.theme.CellularMonitorTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        // 授权结果在 onResume 里 controller.retry() 会自动应用，这里不用管。
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CellularMonitorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户从设置页返回时，controller.retry() 会重新评估权限与定位开关。
        // 这里不直接调用 retry，由 MainScreen 的 LaunchedEffect(Unit) 触发一次检查。
    }

    @Composable
    private fun MainScreen() {
        val controller = rememberMonitorController(this)
        val status = controller.status

        // 启动时若权限未齐，自动请求一次。
        LaunchedEffect(Unit) {
            if (!status.hasPhoneStatePermission || !status.hasLocationPermission) {
                requestPermissions()
            }
        }

        // 从设置页返回时，用户可能手动开了定位或授了权，刷新状态。
        LaunchedEffect(lifecycle) {
            lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                    controller.retry()
                }
            })
        }

        MonitorScreen(controller)
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
        )
    }
}
