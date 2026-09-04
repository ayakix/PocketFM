package com.ayakix.pocketfm.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `rtl_tcp` プロトコルの TCP クライアント。既定では Android の
 * 「SDR driver」アプリ (`marto.rtl_tcp_andro`) が `localhost:14423` に
 * 立てるサーバへ接続し、12 バイトのヘッダを読み、制御コマンドを送り、
 * I/Q サンプル列を [Flow] として公開する。
 *
 * スレッドモデル: [connect]、各 setter、ソケット I/O はすべて
 * [Dispatchers.IO] 上で動く。このクラス自体は **スレッドセーフではない**。
 * 複数の送信元からコマンドを投げる場合は上位でラップすること。
 *
 * ワイヤフォーマットは [RtlTcpProtocol] を参照。
 */
class RtlTcpClient(
    private val host: String = "localhost",
    private val port: Int = RtlTcpProtocol.DEFAULT_PORT,
    /** [samples] が 1 回に読むバイト数。 */
    private val readBufferSize: Int = 32 * 1024,
    /** TCP 接続のタイムアウト (ms)。 */
    private val connectTimeoutMillis: Int = 5_000,
    /**
     * ソケットの読み取りタイムアウト (ms)。`InputStream.read()` は
     * コルーチンのキャンセルを直接見ないため、非ゼロにして定期的に
     * 戻らせ、[samples] がキャンセルを観測できるようにする。
     */
    private val readPollTimeoutMillis: Int = 1_000,
) : Closeable {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    /** サーバヘッダの情報。[connect] が成功するまでは `null`。 */
    var serverInfo: ServerInfo? = null
        private set

    /**
     * TCP 接続を開き、12 バイトの挨拶ヘッダを解釈する。
     * setter や [samples] より先に必ず呼ぶこと。
     */
    suspend fun connect(): ServerInfo = withContext(Dispatchers.IO) {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            s.tcpNoDelay = true
            s.soTimeout = readPollTimeoutMillis

            val inp = DataInputStream(s.getInputStream())
            val out = DataOutputStream(s.getOutputStream())

            // --- 12 バイトのヘッダを読む ---
            val magic = ByteArray(4)
            inp.readFully(magic)
            val magicAscii = String(magic, Charsets.US_ASCII)
            require(magicAscii == RtlTcpProtocol.MAGIC) {
                "rtl_tcp のマジックが不正: 期待値 '${RtlTcpProtocol.MAGIC}' に対し '$magicAscii'"
            }
            val tunerType = inp.readInt()
            val gainStages = inp.readInt()

            socket = s
            input = inp
            output = out
            ServerInfo(tunerType, gainStages).also { serverInfo = it }
        } catch (t: Throwable) {
            // connect() からヘッダ解釈までの間で失敗しても OS ソケットを漏らさない。
            runCatching { s.close() }
            throw t
        }
    }

    /** FM 放送受信の初期設定をまとめて適用する。 */
    suspend fun applyFmDefaults(
        frequencyHz: Int,
        sampleRateHz: Int = RtlTcpProtocol.FM_DEVICE_SAMPLE_RATE_HZ,
        tunerGainTenthsDb: Int = RtlTcpProtocol.FM_DEFAULT_TUNER_GAIN_TENTHS_DB,
        /** 手動ゲイン。null を渡すとチューナの自動ゲインに任せる。 */
        manualGain: Boolean = true,
    ) {
        setSampleRate(sampleRateHz)
        setFrequency(frequencyHz)
        // RTL2832U 側のデジタル AGC は FM だと過補償で歪むことがあるので切る。
        setAgcMode(false)
        setGainMode(manual = manualGain)
        if (manualGain) setTunerGain(tunerGainTenthsDb)
    }

    suspend fun setFrequency(hz: Int) =
        sendCommand(RtlTcpProtocol.CMD_SET_FREQUENCY, hz)

    suspend fun setSampleRate(hz: Int) =
        sendCommand(RtlTcpProtocol.CMD_SET_SAMPLE_RATE, hz)

    suspend fun setGainMode(manual: Boolean) =
        sendCommand(RtlTcpProtocol.CMD_SET_GAIN_MODE, if (manual) 1 else 0)

    suspend fun setTunerGain(tenthsOfDb: Int) =
        sendCommand(RtlTcpProtocol.CMD_SET_TUNER_GAIN, tenthsOfDb)

    suspend fun setAgcMode(on: Boolean) =
        sendCommand(RtlTcpProtocol.CMD_SET_AGC_MODE, if (on) 1 else 0)

    suspend fun setFrequencyCorrectionPpm(ppm: Int) =
        sendCommand(RtlTcpProtocol.CMD_SET_FREQUENCY_CORRECTION, ppm)

    /**
     * I/Q サンプルのストリーム。emit される [ByteArray] は u8 I, u8 Q の
     * ペア列の一部。[Dispatchers.IO] 上で動き、サーバが切断すると完了する。
     *
     * キャンセル: `InputStream.read()` は JVM のブロッキング呼び出しで
     * コルーチンのキャンセルを直接見ない。[connect] で設定した `soTimeout`
     * により [readPollTimeoutMillis] ごとに read が戻るので、その粒度で
     * キャンセルが効く。即座に止めたい場合は collector の外から [close] を
     * 呼べば、進行中の read が `SocketException` で抜ける。
     */
    fun samples(): Flow<ByteArray> = flow {
        val inp = checkNotNull(input) { "未接続。先に connect() を呼ぶこと" }
        val buffer = ByteArray(readBufferSize)
        while (currentCoroutineContext().isActive) {
            val read = try {
                inp.read(buffer)
            } catch (_: SocketTimeoutException) {
                // soTimeout による空振り。ループしてキャンセルを観測させる。
                continue
            }
            if (read < 0) break // サーバが切断した
            // 防御的コピー: 呼び出し側に、下で書き換わるバッファを見せない。
            emit(buffer.copyOfRange(0, read))
        }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        try {
            socket?.close()
        } finally {
            socket = null
            input = null
            output = null
        }
    }

    private suspend fun sendCommand(code: Byte, param: Int) = withContext(Dispatchers.IO) {
        val out = checkNotNull(output) { "未接続。先に connect() を呼ぶこと" }
        val packet = ByteBuffer.allocate(RtlTcpProtocol.COMMAND_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(code)
            .putInt(param)
            .array()
        out.write(packet)
        out.flush()
    }
}
