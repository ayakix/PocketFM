package com.ayakix.pocketfm

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayakix.pocketfm.driver.SdrDriver
import com.ayakix.pocketfm.ui.RadioScreen
import com.ayakix.pocketfm.ui.RadioViewModel
import com.ayakix.pocketfm.ui.theme.PocketFmTheme

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 結果は参考情報。拒否されてもフォアグラウンドサービスは動くが、
            // 状態通知が見えなくなるだけ。
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureNotificationPermission()

        setContent {
            PocketFmTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val viewModel: RadioViewModel = viewModel(
                        factory = RadioViewModel.Factory(application),
                    )

                    // 受信開始は必ず SDR ドライバのインテントを経由する。
                    // ドライバはこの要求を受けて初めて rtl_tcp を立ち上げ、
                    // その往復が USB 権限ダイアログを出す唯一の手段でもある。
                    // 成功が返ってから受信を始めるので、ドングルを拒否された
                    // 場合は素のソケットエラーではなくドライバ自身の診断が出る。
                    val driverLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult(),
                    ) { result ->
                        if (result.resultCode == Activity.RESULT_OK) {
                            viewModel.startPlayback()
                        } else {
                            viewModel.reportError(SdrDriver.failureMessage(result.data))
                        }
                    }

                    val context = LocalContext.current
                    LaunchedEffect(Unit) {
                        viewModel.errors.collect { message ->
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }

                    RadioScreen(
                        viewModel = viewModel,
                        onRequestPlay = {
                            try {
                                driverLauncher.launch(SdrDriver.openIntent())
                            } catch (e: ActivityNotFoundException) {
                                viewModel.reportError(SdrDriver.NOT_INSTALLED_MESSAGE)
                            }
                        },
                    )
                }
            }
        }
    }

    private fun ensureNotificationPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
