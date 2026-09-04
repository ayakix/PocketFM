package com.ayakix.pocketfm.radio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 窓関数法によるローパス FIR フィルタの係数設計。
 *
 * 理想ローパスのインパルス応答 (sinc) を Hamming 窓で打ち切る、いちばん
 * 素直な方法。Parks-McClellan のような最適設計ではないが、係数の意味が
 * そのままコードに出るので追いやすい。遷移帯域はおよそ `3.3 / taps`
 * (正規化周波数) になるので、必要な急峻さからタップ数を決められる。
 */
object FirDesign {

    /**
     * カットオフ [cutoffHz] のローパス係数を返す。
     *
     * @param taps 係数の数。中心サンプルを 1 つ持たせるため奇数であること。
     */
    fun lowPass(taps: Int, cutoffHz: Double, sampleRateHz: Double): DoubleArray {
        require(taps % 2 == 1) { "taps は中心を持たせるため奇数であること: $taps" }
        require(cutoffHz > 0 && cutoffHz < sampleRateHz / 2) {
            "カットオフはナイキスト未満であること: $cutoffHz / $sampleRateHz"
        }
        val fc = cutoffHz / sampleRateHz // 正規化カットオフ (0..0.5)
        val mid = taps / 2
        val h = DoubleArray(taps)
        var sum = 0.0
        for (n in 0 until taps) {
            val k = n - mid
            val sinc = if (k == 0) 2.0 * fc else sin(2.0 * PI * fc * k) / (PI * k)
            val window = 0.54 - 0.46 * cos(2.0 * PI * n / (taps - 1))
            h[n] = sinc * window
            sum += h[n]
        }
        // DC ゲインを 1 に正規化する。こうしないと振幅がタップ数と窓の
        // 選び方に依存してしまい、後段の音量スケーリングが決められない。
        for (n in 0 until taps) h[n] /= sum
        return h
    }
}

/**
 * 複素 (I/Q) 信号用の間引きローパスフィルタ。
 *
 * 出力するサンプルの分だけしか畳み込みを計算しない (ポリフェーズの素朴版)。
 * 1/[factor] に落とす場合、計算量も 1/[factor] で済む。
 *
 * 呼び出し間で履歴を保持する **ステートフル** なクラス。連続ストリームを
 * チャンクに分けて渡してもフィルタが途切れないようにするためで、
 * 1 インスタンスを 1 ストリームに対応させること。
 */
class ComplexDecimator(
    private val taps: DoubleArray,
    private val factor: Int,
) {
    private val histI = DoubleArray(taps.size)
    private val histQ = DoubleArray(taps.size)
    private var pos = 0
    private var phase = 0

    /** 入力 [inputSamples] サンプルに対する出力数の上限。バッファ確保用。 */
    fun maxOutput(inputSamples: Int): Int = inputSamples / factor + 1

    /**
     * [n] サンプル分を処理し、[outI]/[outQ] に書いた出力数を返す。
     */
    fun process(
        inI: DoubleArray,
        inQ: DoubleArray,
        n: Int,
        outI: DoubleArray,
        outQ: DoubleArray,
    ): Int {
        val size = taps.size
        var out = 0
        for (k in 0 until n) {
            histI[pos] = inI[k]
            histQ[pos] = inQ[k]
            pos++
            if (pos == size) pos = 0
            phase++
            if (phase < factor) continue
            phase = 0
            var accI = 0.0
            var accQ = 0.0
            var idx = pos // pos は今いちばん古いサンプルを指している
            for (t in 0 until size) {
                val c = taps[t]
                accI += c * histI[idx]
                accQ += c * histQ[idx]
                idx++
                if (idx == size) idx = 0
            }
            outI[out] = accI
            outQ[out] = accQ
            out++
        }
        return out
    }
}

/** [ComplexDecimator] の実数 1 チャンネル版。復調後の音声帯で使う。 */
class RealDecimator(
    private val taps: DoubleArray,
    private val factor: Int,
) {
    private val hist = DoubleArray(taps.size)
    private var pos = 0
    private var phase = 0

    fun maxOutput(inputSamples: Int): Int = inputSamples / factor + 1

    fun process(input: DoubleArray, n: Int, out: DoubleArray): Int {
        val size = taps.size
        var written = 0
        for (k in 0 until n) {
            hist[pos] = input[k]
            pos++
            if (pos == size) pos = 0
            phase++
            if (phase < factor) continue
            phase = 0
            var acc = 0.0
            var idx = pos
            for (t in 0 until size) {
                acc += taps[t] * hist[idx]
                idx++
                if (idx == size) idx = 0
            }
            out[written++] = acc
        }
        return written
    }
}
