package com.ayakix.pocketfm.radio

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * ワイド FM (放送 FM) のモノラル復調器。u8 I/Q ストリームを 16bit PCM に変換する。
 *
 * ADS-B の [パルス位置変調] と違い FM は連続波なので、フレーム検出ではなく
 * 「位相の変化量 = 瞬時周波数」を取り出す処理になる。
 *
 * パイプライン (既定値: 1.2 MS/s 入力 → 48 kHz 音声):
 *
 * ```
 *  u8 I/Q  1.2 MS/s
 *    │ 1. 複素数化           (b - 127.5) / 127.5
 *    │ 2. 複素 LPF + 1/5 間引き   カットオフ 100 kHz → 240 kHz
 *    │ 3. 位相判別器          d[n] = arg(z[n] · conj(z[n-1]))
 *    │ 4. DC 除去            同調ずれによる直流分を抜く
 *    │ 5. 実数 LPF + 1/5 間引き   カットオフ 15 kHz → 48 kHz
 *    │                        ここで 19 kHz パイロットと 38 kHz ステレオ
 *    │                        副搬送波も一緒に落ちる (モノラル化)
 *    │ 6. デエンファシス      1 次 IIR (日本/欧州は 50 μs)
 *    │ 7. 音量 + クリップ
 *  Int16 PCM  48 kHz
 * ```
 *
 * 呼び出し間でフィルタ履歴と直前サンプルを保持する **ステートフル** かつ
 * **スレッドセーフでない** クラス。1 インスタンスを 1 受信セッションに
 * 対応させ、選局し直したときは [reset] を呼ぶこと。
 */
class FmDemodulator(
    /** rtl_tcp から届く生 I/Q のレート。 */
    val inputRateHz: Int = RtlTcpProtocol.FM_DEVICE_SAMPLE_RATE_HZ,
    /** 復調を行う中間レート。WFM の占有帯域を通せる必要がある。 */
    val ifRateHz: Int = RtlTcpProtocol.FM_IF_SAMPLE_RATE_HZ,
    /** 出力音声のレート。 */
    val audioRateHz: Int = RtlTcpProtocol.FM_AUDIO_SAMPLE_RATE_HZ,
    /** デエンファシスの時定数。日本・欧州は 50 μs、北米は 75 μs。 */
    val deemphasisSeconds: Double = 50e-6,
    /** FM 放送の最大周波数偏移。フルスケールの基準に使う。 */
    val maxDeviationHz: Double = 75_000.0,
) {

    private val ifDecimation = inputRateHz / ifRateHz
    private val audioDecimation = ifRateHz / audioRateHz

    init {
        require(inputRateHz % ifRateHz == 0) {
            "入力レートは中間レートの整数倍であること: $inputRateHz / $ifRateHz"
        }
        require(ifRateHz % audioRateHz == 0) {
            "中間レートは音声レートの整数倍であること: $ifRateHz / $audioRateHz"
        }
    }

    /**
     * フロントエンドの複素ローパス。カットオフは WFM の片側帯域幅 100 kHz。
     * タップ数は遷移帯域 (約 `3.3 / taps × 入力レート` = 約 39 kHz) が
     * 間引き後のナイキスト 120 kHz までに収まるように選んだ。増やすほど
     * 隣接局の混入は減るが、そのぶん CPU を食う。
     */
    private val frontEnd = ComplexDecimator(
        taps = FirDesign.lowPass(
            taps = if (ifDecimation > 1) 101 else 1,
            cutoffHz = 100_000.0,
            sampleRateHz = inputRateHz.toDouble(),
        ),
        factor = ifDecimation,
    )

    /** 音声帯ローパス。15 kHz でモノラル音声だけを残す。 */
    private val audioFilter = RealDecimator(
        taps = FirDesign.lowPass(
            taps = 101,
            cutoffHz = 15_000.0,
            sampleRateHz = ifRateHz.toDouble(),
        ),
        factor = audioDecimation,
    )

    /**
     * 判別器出力をフルスケール ±1.0 に正規化する係数。最大偏移のとき
     * 1 サンプルあたりの位相変化は `2π · maxDeviation / ifRate` になる。
     */
    private val discriminatorScale = 1.0 / (2.0 * PI * maxDeviationHz / ifRateHz)

    /**
     * 1 次 IIR デエンファシスの係数。`α = 1 - exp(-1 / (fs · τ))`。
     * 送信側のプリエンファシス (高域持ち上げ) を打ち消して、
     * 高域のヒスノイズを本来のレベルまで戻す。
     */
    private val deemphasisAlpha = 1.0 - exp(-1.0 / (audioRateHz * deemphasisSeconds))

    /**
     * DC 除去フィルタの極。カットオフはおよそ `(1 - r) · fs / 2π`。
     * ifRate 240 kHz なら約 19 Hz で、可聴帯には触れない。
     * 同調誤差 (PPM ずれ) は判別器出力の直流分として現れるので、
     * これを抜かないと音量が偏ったりクリップしたりする。
     */
    private val dcBlockPole = 1.0 - (2.0 * PI * 20.0 / ifRateHz)

    // --- 呼び出しをまたいで保持する状態 -------------------------------------------

    /** I/Q ペアの途中でチャンクが切れた場合に持ち越す 1 バイト。 */
    private var pendingByte: Int = -1

    /** 位相判別に必要な直前の複素サンプル。 */
    private var prevI = 0.0
    private var prevQ = 0.0

    private var dcLastIn = 0.0
    private var dcLastOut = 0.0
    private var deemphasisState = 0.0

    /** 出力音量 (0.0..1.0 想定)。実行中に変更してよい。 */
    @Volatile
    var volume: Double = 0.8

    // --- 使い回すスクラッチバッファ ----------------------------------------------
    // チャンクごとに数百 KB を new すると GC が音切れの原因になるので、
    // 必要になった最大サイズまで伸ばして使い回す。

    private var scratchI = DoubleArray(0)
    private var scratchQ = DoubleArray(0)
    private var ifI = DoubleArray(0)
    private var ifQ = DoubleArray(0)
    private var discriminated = DoubleArray(0)
    private var audio = DoubleArray(0)

    /** 選局し直したときなど、フィルタ履歴を捨てて初期状態に戻す。 */
    fun reset() {
        pendingByte = -1
        prevI = 0.0
        prevQ = 0.0
        dcLastIn = 0.0
        dcLastOut = 0.0
        deemphasisState = 0.0
    }

    /**
     * I/Q チャンクを復調して 16bit モノラル PCM を返す。
     * 入力が短いと空配列が返ることもある (フィルタの遅延のため)。
     */
    fun demodulate(iq: ByteArray): ShortArray {
        if (iq.isEmpty()) return ShortArray(0)

        val hadPending = pendingByte >= 0
        val available = iq.size + if (hadPending) 1 else 0
        val sampleCount = available / 2
        // TCP は I/Q ペアの途中で読み取りを切る。余った末尾 1 バイトを
        // 捨てると以降の I と Q が入れ替わり、まったく復調できなくなるので
        // 次のチャンクへ持ち越す。
        val nextPending =
            if (available % 2 == 1) iq[iq.size - 1].toInt() and 0xFF else -1

        if (sampleCount == 0) {
            pendingByte = nextPending
            return ShortArray(0)
        }
        ensureCapacity(sampleCount)
        unpack(iq, sampleCount, hadPending)
        pendingByte = nextPending

        val ifCount = frontEnd.process(scratchI, scratchQ, sampleCount, ifI, ifQ)
        val audioInCount = discriminate(ifCount)
        val audioCount = audioFilter.process(discriminated, audioInCount, audio)
        return toPcm(audioCount)
    }

    // --- 各段の実装 --------------------------------------------------------------

    /**
     * u8 I/Q を ±1.0 の実数ペアへ展開する。RTL2832U の出力は 0..255 の
     * オフセットバイナリなので、中点 127.5 を引いて符号付きに直す。
     */
    private fun unpack(iq: ByteArray, sampleCount: Int, hadPending: Boolean) {
        var src = 0
        var dst = 0
        if (hadPending) {
            // 前チャンクの末尾 I と今チャンクの先頭 Q でペアを復元する。
            scratchI[0] = (pendingByte - 127.5) / 127.5
            scratchQ[0] = ((iq[0].toInt() and 0xFF) - 127.5) / 127.5
            src = 1
            dst = 1
        }
        while (dst < sampleCount) {
            scratchI[dst] = ((iq[src].toInt() and 0xFF) - 127.5) / 127.5
            scratchQ[dst] = ((iq[src + 1].toInt() and 0xFF) - 127.5) / 127.5
            src += 2
            dst++
        }
    }

    /**
     * 位相判別器。`z[n] · conj(z[n-1])` の偏角が 1 サンプルあたりの位相
     * 変化、すなわち瞬時周波数に比例する。FM は周波数に情報を載せるので、
     * これがそのまま復調出力になる。
     *
     * 続けて DC 除去も掛ける。
     */
    private fun discriminate(n: Int): Int {
        var pi = prevI
        var pq = prevQ
        var lastIn = dcLastIn
        var lastOut = dcLastOut
        for (k in 0 until n) {
            val ci = ifI[k]
            val cq = ifQ[k]
            // z[n] * conj(z[n-1])
            val real = ci * pi + cq * pq
            val imag = cq * pi - ci * pq
            val phase = if (real == 0.0 && imag == 0.0) 0.0 else atan2(imag, real)
            val value = phase * discriminatorScale

            // DC ブロッカ: y[n] = x[n] - x[n-1] + r·y[n-1]
            val out = value - lastIn + dcBlockPole * lastOut
            lastIn = value
            lastOut = out
            discriminated[k] = out

            pi = ci
            pq = cq
        }
        prevI = pi
        prevQ = pq
        dcLastIn = lastIn
        dcLastOut = lastOut
        return n
    }

    /** デエンファシスを掛けて Int16 PCM に落とす。 */
    private fun toPcm(n: Int): ShortArray {
        val pcm = ShortArray(n)
        var state = deemphasisState
        val gain = volume
        for (k in 0 until n) {
            state += deemphasisAlpha * (audio[k] - state)
            val scaled = (state * gain * Short.MAX_VALUE).roundToInt()
            // クリップ。歪むが、ラップアラウンドして激しいノイズになるよりまし。
            pcm[k] = when {
                scaled > Short.MAX_VALUE.toInt() -> Short.MAX_VALUE
                scaled < Short.MIN_VALUE.toInt() -> Short.MIN_VALUE
                else -> scaled.toShort()
            }
        }
        deemphasisState = state
        return pcm
    }

    private fun ensureCapacity(sampleCount: Int) {
        if (scratchI.size < sampleCount) {
            scratchI = DoubleArray(sampleCount)
            scratchQ = DoubleArray(sampleCount)
        }
        val ifCapacity = frontEnd.maxOutput(sampleCount)
        if (ifI.size < ifCapacity) {
            ifI = DoubleArray(ifCapacity)
            ifQ = DoubleArray(ifCapacity)
            discriminated = DoubleArray(ifCapacity)
        }
        val audioCapacity = audioFilter.maxOutput(ifCapacity)
        if (audio.size < audioCapacity) {
            audio = DoubleArray(audioCapacity)
        }
    }
}
