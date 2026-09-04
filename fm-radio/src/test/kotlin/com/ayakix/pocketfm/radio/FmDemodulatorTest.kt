package com.ayakix.pocketfm.radio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * 実機なしで復調器を検証する。既知のトーンで FM 変調した I/Q を合成し、
 * 復調結果からそのトーンが取り出せるかを見る。
 */
class FmDemodulatorTest {

    /**
     * [toneHz] の正弦波で周波数変調した u8 I/Q を生成する。
     * rtl_tcp が流してくるものと同じ形式 (オフセットバイナリ、I/Q 交互)。
     */
    private fun synthesizeFm(
        sampleRateHz: Int,
        durationSeconds: Double,
        toneHz: Double,
        deviationHz: Double = 75_000.0,
        /** 受信機のフルスケールに対する信号の振幅。実際の受信でも 1.0 未満。 */
        amplitude: Double = 0.7,
    ): ByteArray {
        val n = (sampleRateHz * durationSeconds).toInt()
        val iq = ByteArray(n * 2)
        var phase = 0.0
        for (k in 0 until n) {
            val t = k.toDouble() / sampleRateHz
            // 瞬時周波数 = deviation · sin(2π·tone·t) を積分したものが位相。
            val instantaneousHz = deviationHz * sin(2.0 * PI * toneHz * t)
            phase += 2.0 * PI * instantaneousHz / sampleRateHz
            val i = amplitude * cos(phase)
            val q = amplitude * sin(phase)
            iq[2 * k] = ((i * 127.5 + 127.5).roundToInt().coerceIn(0, 255)).toByte()
            iq[2 * k + 1] = ((q * 127.5 + 127.5).roundToInt().coerceIn(0, 255)).toByte()
        }
        return iq
    }

    /** ゼロ交差の数から周波数を推定する。 */
    private fun estimateFrequency(pcm: ShortArray, from: Int, sampleRateHz: Int): Double {
        var crossings = 0
        for (k in from + 1 until pcm.size) {
            val a = pcm[k - 1].toInt()
            val b = pcm[k].toInt()
            if ((a < 0 && b >= 0) || (a >= 0 && b < 0)) crossings++
        }
        val spanSeconds = (pcm.size - from).toDouble() / sampleRateHz
        // 1 周期にゼロ交差は 2 回。
        return crossings / 2.0 / spanSeconds
    }

    @Test
    fun `1kHzのトーンをFM変調した信号から同じトーンを復調できる`() {
        val demod = FmDemodulator()
        val iq = synthesizeFm(
            sampleRateHz = demod.inputRateHz,
            durationSeconds = 0.5,
            toneHz = 1_000.0,
        )
        val pcm = demod.demodulate(iq)

        // 0.5 秒 × 48 kHz。フィルタ遅延の分だけ数サンプル前後する。
        assertTrue(pcm.size > 23_000, "出力サンプル数が少なすぎる: ${pcm.size}")

        // フィルタと DC ブロッカの過渡応答が収まるまでを読み飛ばす。
        val settled = 4_000
        val estimated = estimateFrequency(pcm, settled, demod.audioRateHz)
        assertTrue(
            abs(estimated - 1_000.0) < 20.0,
            "復調されたトーンが 1 kHz から離れている: $estimated Hz",
        )

        // 最大偏移で変調しているので、ほぼフルスケールまで振れるはず。
        val peak = (settled until pcm.size).maxOf { abs(pcm[it].toInt()) }
        assertTrue(peak > 18_000, "振幅が小さすぎる (peak=$peak)")
        assertTrue(peak <= Short.MAX_VALUE.toInt(), "クリップ判定の破綻 (peak=$peak)")
    }

    @Test
    fun `デエンファシスが高域を落とす`() {
        // 10 kHz は 50 μs デエンファシス (折点 約3.2 kHz) で明確に減衰する。
        fun peakFor(toneHz: Double): Int {
            val demod = FmDemodulator()
            val pcm = demod.demodulate(
                synthesizeFm(demod.inputRateHz, 0.3, toneHz),
            )
            return (4_000 until pcm.size).maxOf { abs(pcm[it].toInt()) }
        }
        val low = peakFor(300.0)
        val high = peakFor(10_000.0)
        assertTrue(high < low / 2, "10 kHz が十分減衰していない: low=$low high=$high")
    }

    @Test
    fun `チャンク分割してもIQペアがずれず結果が変わらない`() {
        val reference = FmDemodulator()
        val iq = synthesizeFm(reference.inputRateHz, 0.2, 1_000.0)
        val whole = reference.demodulate(iq)

        // 奇数長で切って、I/Q ペアの途中で必ず分割が起きるようにする。
        val chunked = FmDemodulator()
        val pieces = mutableListOf<Short>()
        var offset = 0
        var size = 4_097
        while (offset < iq.size) {
            val end = minOf(offset + size, iq.size)
            chunked.demodulate(iq.copyOfRange(offset, end)).forEach { pieces += it }
            offset = end
            size = if (size == 4_097) 1_023 else 4_097
        }

        assertContentEquals(whole, pieces.toShortArray())
    }

    @Test
    fun `ローパス係数のDCゲインは1`() {
        val taps = FirDesign.lowPass(taps = 101, cutoffHz = 15_000.0, sampleRateHz = 240_000.0)
        assertTrue(abs(taps.sum() - 1.0) < 1e-12, "DC ゲインが 1 でない: ${taps.sum()}")
    }
}
