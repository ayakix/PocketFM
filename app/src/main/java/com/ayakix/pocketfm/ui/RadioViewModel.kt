package com.ayakix.pocketfm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ayakix.pocketfm.PocketFmApp
import com.ayakix.pocketfm.TunerState
import com.ayakix.pocketfm.service.RadioForegroundService
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 画面の状態と操作の入口。実際の受信は [RadioForegroundService] が持つので、
 * ここは設定値の中継とサービスの起動・停止だけを担う。
 *
 * DI フレームワークは使わず、[Factory] で `Application` を直接渡している。
 * 依存が 1 つしかない規模で DI を持ち込む理由がないため。
 */
class RadioViewModel(application: Application) : AndroidViewModel(application) {

    private val app: PocketFmApp get() = getApplication()

    val frequencyHz: StateFlow<Int> = app.tuner.frequencyHz
    val gainTenthsDb: StateFlow<Int> = app.tuner.gainTenthsDb
    val volume: StateFlow<Float> = app.tuner.volume
    val isPlaying: StateFlow<Boolean> = RadioForegroundService.isPlaying
    val errors: SharedFlow<String> = app.errors

    fun tune(hz: Int) = app.tuner.tune(hz)
    fun step(channels: Int) = app.tuner.step(channels)
    fun setGain(tenthsDb: Int) = app.tuner.setGain(tenthsDb)
    fun setVolume(value: Float) = app.tuner.setVolume(value)

    /**
     * SDR ドライバがドングルを開けたあとに呼ぶ。ドライバの起動インテントは
     * Activity の ActivityResult ランチャーが撃つので、ここでは受信の開始
     * だけを行う。
     */
    fun startPlayback() = RadioForegroundService.start(getApplication())

    fun stopPlayback() = RadioForegroundService.stop(getApplication())

    fun reportError(message: String) = app.postError(message)

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RadioViewModel(application) as T
    }
}

/** UI 表示用に Hz を "82.5" のような MHz 文字列にする。 */
fun Int.toMhzLabel(): String = "%.1f".format(this / 1_000_000.0)

/** 周波数をバンド内の 0..1 の位置に写す。スライダー用。 */
fun Int.toBandFraction(): Float {
    val min = com.ayakix.pocketfm.radio.RtlTcpProtocol.FM_BAND_MIN_HZ
    val max = com.ayakix.pocketfm.radio.RtlTcpProtocol.FM_BAND_MAX_HZ
    return ((this - min).toFloat() / (max - min)).coerceIn(0f, 1f)
}

/** [toBandFraction] の逆。チャンネルグリッドに吸着した Hz を返す。 */
fun Float.toBandFrequencyHz(): Int {
    val min = com.ayakix.pocketfm.radio.RtlTcpProtocol.FM_BAND_MIN_HZ
    val max = com.ayakix.pocketfm.radio.RtlTcpProtocol.FM_BAND_MAX_HZ
    return TunerState.snapToChannel((min + this * (max - min)).toInt())
}
