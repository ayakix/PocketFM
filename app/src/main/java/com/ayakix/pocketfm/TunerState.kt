package com.ayakix.pocketfm

import android.content.Context
import com.ayakix.pocketfm.radio.RtlTcpProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * チューナの設定値。UI が書き、受信サービスが読む。
 *
 * StateFlow で持つのは、再生中に選局やゲインを変えたときソケットを
 * 張り直さずコマンドだけで反映させたいため。ドライバはクライアントが
 * 切断すると `rtl_tcp` ごと終了してしまい、`iqsrc://` インテントを
 * 撃ち直さない限り再接続できない。
 *
 * 値は SharedPreferences に保存する。アプリを開き直すたびに周波数を
 * 入れ直させないため。
 */
class TunerState(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _frequencyHz = MutableStateFlow(
        prefs.getInt(KEY_FREQUENCY, DEFAULT_FREQUENCY_HZ),
    )
    val frequencyHz: StateFlow<Int> = _frequencyHz.asStateFlow()

    private val _gainTenthsDb = MutableStateFlow(
        prefs.getInt(KEY_GAIN, RtlTcpProtocol.FM_DEFAULT_TUNER_GAIN_TENTHS_DB),
    )
    val gainTenthsDb: StateFlow<Int> = _gainTenthsDb.asStateFlow()

    private val _volume = MutableStateFlow(prefs.getFloat(KEY_VOLUME, 0.8f))
    val volume: StateFlow<Float> = _volume.asStateFlow()

    /** 放送band 内に丸め、100 kHz グリッドに吸着させてから設定する。 */
    fun tune(hz: Int) {
        val snapped = snapToChannel(hz)
        if (snapped == _frequencyHz.value) return
        _frequencyHz.value = snapped
        prefs.edit().putInt(KEY_FREQUENCY, snapped).apply()
    }

    /** 1 チャンネル (100 kHz) 単位で上下する。 */
    fun step(channels: Int) {
        tune(_frequencyHz.value + channels * RtlTcpProtocol.FM_CHANNEL_STEP_HZ)
    }

    fun setGain(tenthsDb: Int) {
        val clamped = tenthsDb.coerceIn(MIN_GAIN_TENTHS_DB, MAX_GAIN_TENTHS_DB)
        if (clamped == _gainTenthsDb.value) return
        _gainTenthsDb.value = clamped
        prefs.edit().putInt(KEY_GAIN, clamped).apply()
    }

    fun setVolume(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (clamped == _volume.value) return
        _volume.value = clamped
        prefs.edit().putFloat(KEY_VOLUME, clamped).apply()
    }

    companion object {
        private const val PREFS = "tuner"
        private const val KEY_FREQUENCY = "frequency_hz"
        private const val KEY_GAIN = "gain_tenths_db"
        private const val KEY_VOLUME = "volume"

        /** 初期選局。日本の FM band のほぼ中央。 */
        const val DEFAULT_FREQUENCY_HZ: Int = 82_500_000

        /**
         * ゲインの可変範囲。R820T/R828D の実際の段は離散的だが、ドライバ側が
         * 最も近い段に丸めてくれるので UI は連続値として扱ってよい。
         */
        const val MIN_GAIN_TENTHS_DB: Int = 0
        const val MAX_GAIN_TENTHS_DB: Int = 496

        fun snapToChannel(hz: Int): Int {
            val step = RtlTcpProtocol.FM_CHANNEL_STEP_HZ
            val clamped = hz.coerceIn(RtlTcpProtocol.FM_BAND_MIN_HZ, RtlTcpProtocol.FM_BAND_MAX_HZ)
            return ((clamped + step / 2) / step) * step
        }
    }
}
