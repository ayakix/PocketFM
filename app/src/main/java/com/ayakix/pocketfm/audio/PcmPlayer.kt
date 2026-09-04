package com.ayakix.pocketfm.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.Closeable

/**
 * 復調済み 16bit モノラル PCM を鳴らすだけの [AudioTrack] ラッパー。
 *
 * [write] はブロッキングで書き込む。これが復調パイプライン全体の
 * バックプレッシャになる — AudioTrack のバッファが埋まっている間は
 * 呼び出し元が待ち、rtl_tcp 側の受信バッファに溜まる。復調を先に走らせて
 * 自前でキューを持つより、遅延が読みやすい。
 */
class PcmPlayer(
    private val sampleRateHz: Int,
    /**
     * AudioTrack のバッファをハードウェア最小値の何倍にするか。
     * 大きいほどアンダーラン (音切れ) に強いが、そのぶん遅延が増える。
     * 選局の操作感を損なわない範囲で余裕を持たせている。
     */
    bufferSizeMultiplier: Int = 4,
) : Closeable {

    private val minBufferBytes = AudioTrack.getMinBufferSize(
        sampleRateHz,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRateHz)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(minBufferBytes * bufferSizeMultiplier)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    fun start() {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
    }

    /** バッファが空くまでブロックしながら書き込む。 */
    fun write(pcm: ShortArray) {
        track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
    }

    /** 0.0..1.0。無音側は完全な 0 にできる。 */
    fun setVolume(volume: Float) {
        track.setVolume(volume.coerceIn(0f, AudioTrack.getMaxVolume()))
    }

    /**
     * 選局し直したときなど、再生済みキューを捨てる。捨てないと
     * 前の局の音が数百 ms 残ってから新しい局に切り替わる。
     */
    fun flush() {
        track.pause()
        track.flush()
        track.play()
    }

    override fun close() {
        runCatching { track.stop() }
        track.release()
    }

    /** AudioTrack の生成に失敗していないか。端末がレートを拒否した場合など。 */
    val isReady: Boolean get() = track.state == AudioTrack.STATE_INITIALIZED
}
