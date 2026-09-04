package com.ayakix.pocketfm.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 1 回の FM 受信セッション。[RtlTcpClient] (I/Q over TCP) と
 * [FmDemodulator] (I/Q → PCM) を繋いで `Flow<ShortArray>` を作る。
 *
 * ライフサイクル: [audio] を collect するたびに新しいソケットを開き、
 * FM の初期設定を適用し、collector がキャンセルするかサーバが切断する
 * まで復調し続ける。ソケットは `RtlTcpClient.use` で確実に閉じる。
 *
 * 選局: [frequencyHz] は StateFlow で受け取り、値が変わるたびに
 * `CMD_SET_FREQUENCY` を送る。ソケットを張り直さずに済むので、
 * 選局してから音が出るまでが速い。
 *
 * エラー: 初回接続が [connectTimeoutMillis] を過ぎても失敗し続ける場合、
 * 最後の例外が collector に終端エラーとして伝わる。呼び出し側は
 * `.catch { }` などで受けること。
 */
class FmRadioSession(
    private val host: String = "localhost",
    private val port: Int = RtlTcpProtocol.DEFAULT_PORT,
    /** 現在の選局周波数。変化を監視して追従する。 */
    private val frequencyHz: StateFlow<Int>,
    private val demodulator: FmDemodulator = FmDemodulator(),
    /**
     * チューナゲイン (0.1 dB 単位)。周波数と同じく StateFlow で受け取り、
     * 接続を張り直さずにコマンドで反映する。ソケットを切ると Android の
     * SDR ドライバは rtl_tcp ごと終了してしまい、`iqsrc://` インテントを
     * 撃ち直さない限り再接続できないため、再接続は最後の手段にしたい。
     */
    private val gainTenthsDb: StateFlow<Int>,
    /** 手動ゲイン。false ならチューナの自動ゲインに任せる。 */
    private val manualGain: Boolean = true,
    /**
     * 初回接続をリトライし続ける時間。Android の SDR ドライバアプリは
     * デバイスを開く Activity から戻ってから実際に listen を始めるまで
     * 1 秒ほどラグがあり、1 回きりの接続だとこの競合に負けて
     * 「connection refused」に見えてしまう。
     */
    private val connectTimeoutMillis: Long = 10_000,
    /** 接続リトライの間隔。 */
    private val connectRetryDelayMillis: Long = 250,
) {

    /**
     * 復調済み 16bit モノラル PCM のストリーム。サンプルレートは
     * [FmDemodulator.audioRateHz]。
     */
    fun audio(): Flow<ShortArray> = channelFlow {
        RtlTcpClient(host, port).use { rtl ->
            connectWithRetry(rtl)
            rtl.applyFmDefaults(
                frequencyHz = frequencyHz.value,
                sampleRateHz = demodulator.inputRateHz,
                tunerGainTenthsDb = gainTenthsDb.value,
                manualGain = manualGain,
            )

            // 選局・ゲインの追従。applyFmDefaults で現在値は設定済みなので
            // どちらも初回の emit は捨てる。
            val tuner = launch {
                frequencyHz.drop(1).collect { hz ->
                    // フィルタ履歴には前の局の信号が残っている。捨てないと
                    // 選局直後に一瞬ノイズが出る。
                    demodulator.reset()
                    rtl.setFrequency(hz)
                }
            }
            val gainFollower = launch {
                if (manualGain) {
                    gainTenthsDb.drop(1).collect { rtl.setTunerGain(it) }
                }
            }

            try {
                rtl.samples().collect { chunk ->
                    val pcm = demodulator.demodulate(chunk)
                    if (pcm.isNotEmpty()) send(pcm)
                }
            } finally {
                tuner.cancel()
                gainFollower.cancel()
            }
        }
    }
        .buffer(capacity = AUDIO_BUFFER_CHUNKS, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        .flowOn(Dispatchers.Default)

    /**
     * [connectTimeoutMillis] まで接続をリトライする。リトライするのは
     * 接続確立だけ。ヘッダが読めた時点で起動時の競合は抜けているので、
     * それ以降の失敗は本物として報告する価値がある。
     */
    private suspend fun connectWithRetry(rtl: RtlTcpClient) {
        val deadline = System.currentTimeMillis() + connectTimeoutMillis
        while (true) {
            try {
                rtl.connect()
                return
            } catch (e: IOException) {
                if (System.currentTimeMillis() >= deadline) throw e
                delay(connectRetryDelayMillis)
            }
        }
    }

    private companion object {
        /**
         * 復調側と再生側を切り離すためのバッファ段数。AudioTrack への書き込みが
         * 一時的に詰まっても復調を止めないための余裕だが、深くしすぎると
         * 遅延がそのまま増える。溢れた場合は古いほうを捨てる — 音が飛ぶより
         * 遅延が積み上がって選局操作に追従しなくなるほうが体験として悪い。
         */
        const val AUDIO_BUFFER_CHUNKS = 8
    }
}
