package com.ayakix.pocketfm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.ayakix.pocketfm.MainActivity
import com.ayakix.pocketfm.PocketFmApp
import com.ayakix.pocketfm.R
import com.ayakix.pocketfm.audio.PcmPlayer
import com.ayakix.pocketfm.driver.SdrDriver
import com.ayakix.pocketfm.radio.FmDemodulator
import com.ayakix.pocketfm.radio.FmRadioSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 受信〜再生のコルーチンを持つフォアグラウンドサービス。
 *
 * Activity から独立させているのは、画面を消してもバックグラウンドで
 * 鳴り続けさせるため。Android はそのためにフォアグラウンドサービスを
 * 要求し、Android 14 以降は型の宣言も必須になる。音を出すのが仕事なので
 * `mediaPlayback` を使う (USB の所有権は SDR ドライバアプリ側にあるため
 * `connectedDevice` ではない)。
 *
 * **重要**: SDR ドライバはクライアントが切断すると `rtl_tcp` ごと終了する。
 * 一度止めたら、次に再生するときは必ず `iqsrc://` インテントを撃ち直す
 * 必要がある。この判断は UI 側 ([MainActivity] のランチャー) が持っている。
 */
class RadioForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playbackJob: Job? = null

    private val app: PocketFmApp get() = application as PocketFmApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        startPlayback(startId)
        isPlaying.value = true
        // START_NOT_STICKY: プロセスが死んだあと勝手に復活しても、SDR ドライバの
        // rtl_tcp はもう終了しているので接続できない。利用者に再操作させる。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        playbackJob?.cancel()
        scope.cancel()
        isPlaying.value = false
        super.onDestroy()
    }

    private fun startPlayback(startId: Int) {
        playbackJob?.cancel()
        val tuner = app.tuner
        val demodulator = FmDemodulator()
        val session = FmRadioSession(
            host = SdrDriver.HOST,
            frequencyHz = tuner.frequencyHz,
            demodulator = demodulator,
            gainTenthsDb = tuner.gainTenthsDb,
        )

        playbackJob = scope.launch {
            val player = PcmPlayer(demodulator.audioRateHz)
            if (!player.isReady) {
                player.close()
                app.postError("オーディオ出力を初期化できませんでした")
                stopSelf(startId)
                return@launch
            }
            player.setVolume(tuner.volume.value)
            player.start()

            val volumeFollower = launch {
                tuner.volume.collect { player.setVolume(it) }
            }
            // 選局し直したら、再生待ちキューに残っている前の局の音を捨てる。
            val tuneFlusher = launch {
                tuner.frequencyHz.drop(1).collect {
                    player.flush()
                    updateNotification()
                }
            }

            try {
                session.audio().collect { pcm -> player.write(pcm) }
                // ここに来るのはサーバが切断したとき。
                app.postError("SDR ドライバとの接続が切れました")
                stopSelf(startId)
            } catch (t: Throwable) {
                Log.e(TAG, "受信に失敗", t)
                app.postError("rtl_tcp 接続に失敗: ${t::class.java.simpleName}: ${t.message ?: "詳細なし"}")
                // stopSelf(startId) — この collect を起動したあとに新しい
                // onStartCommand が来ていた場合は止めない。引数なしの
                // stopSelf() だと再生し直しと競合する。
                stopSelf(startId)
            } finally {
                volumeFollower.cancel()
                tuneFlusher.cancel()
                player.close()
            }
        }
    }

    private fun promoteToForeground() {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val mhz = app.tuner.frequencyHz.value / 1_000_000.0
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle("PocketFM — %.1f MHz".format(mhz))
            .setContentText("受信中")
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FM 受信",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "FM 受信サービスの状態"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "RadioFgService"
        const val CHANNEL_ID = "pocketfm.receiver"
        private const val NOTIFICATION_ID = 1001

        /** UI から購読できる再生状態。 */
        val isPlaying: MutableStateFlow<Boolean> = MutableStateFlow(false)

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, RadioForegroundService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RadioForegroundService::class.java))
        }
    }
}
