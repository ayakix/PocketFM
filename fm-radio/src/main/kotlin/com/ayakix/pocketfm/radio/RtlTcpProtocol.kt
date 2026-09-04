package com.ayakix.pocketfm.radio

/**
 * rtl_tcp ワイヤプロトコルの定数とコマンドコード。本家 `librtlsdr` の
 * `rtl_tcp` と、Martin Marinov による Android 移植版
 * (`marto.rtl_tcp_andro`) の双方で共通。
 *
 * プロトコル概要:
 *   - 接続直後にサーバから **12 バイトのビッグエンディアンヘッダ** が届く:
 *       4 バイト: ASCII マジック "RTL0"
 *       4 バイト: チューナ種別 (例: 5 = R820T, 6 = R828D)
 *       4 バイト: チューナのゲイン段数
 *   - ヘッダ以降は **u8 I, u8 Q** のインターリーブされたサンプルが
 *     切断まで連続で流れてくる。
 *   - クライアントは任意のタイミングで **5 バイトのコマンド** を送れる:
 *       1 バイト: コマンドコード
 *       4 バイト: パラメータ (ビッグエンディアン uint32)
 */
object RtlTcpProtocol {

    /**
     * rtl_tcp の待ち受けポート。本家 `rtl_tcp` バイナリは **1234** だが、
     * Android 版ドライバ ("SDR driver", パッケージ `marto.rtl_tcp_andro`)
     * は他アプリとの衝突を避けるため **14423** を既定にしている。
     * 本アプリは Android 版ドライバと組む前提なので後者に合わせる。
     */
    const val DEFAULT_PORT: Int = 14423

    /** サーバの挨拶ヘッダのバイト数。 */
    const val HEADER_SIZE: Int = 12

    /** ヘッダ先頭の ASCII マジック。 */
    const val MAGIC: String = "RTL0"

    /** クライアントコマンドのバイト数。 */
    const val COMMAND_SIZE: Int = 5

    // --- コマンドコード ----------------------------------------------------------
    // FM 受信に必要な範囲のみ。全リストは librtlsdr の `rtl_tcp.c` を参照。

    /** 中心周波数を Hz で設定する。 */
    const val CMD_SET_FREQUENCY: Byte = 0x01

    /** サンプルレートを Hz で設定する。 */
    const val CMD_SET_SAMPLE_RATE: Byte = 0x02

    /** チューナのゲインモード。0 = 自動, 1 = 手動。 */
    const val CMD_SET_GAIN_MODE: Byte = 0x03

    /** チューナゲインを 0.1 dB 単位で設定する (例: 490 = 49 dB)。 */
    const val CMD_SET_TUNER_GAIN: Byte = 0x04

    /** 周波数補正を ppm で設定する。 */
    const val CMD_SET_FREQUENCY_CORRECTION: Byte = 0x05

    /** RTL2832U 側のデジタル AGC。0 = off, 1 = on。 */
    const val CMD_SET_AGC_MODE: Byte = 0x08

    // --- FM 放送向けの既定値 -----------------------------------------------------

    /**
     * デバイスのサンプルレート。RTL-SDR が受け付けるのは
     * 225001–300000 Hz と 900001–3200000 Hz の 2 帯だけである。
     *
     * 240 kHz を直接指定することもできるが、RTL2832U は低レートだと
     * 内部デシメーションの段数が減って量子化ノイズが乗りやすい。
     * そこで 1.2 MS/s で受けてソフト側で 1/5 に落とす (rtl_fm の
     * オーバーサンプリングと同じ考え方)。CPU が厳しければ
     * [FmDemodulator] のコンストラクタ引数で 240 kHz 直結に落とせる。
     */
    const val FM_DEVICE_SAMPLE_RATE_HZ: Int = 1_200_000

    /** 復調を行う中間レート。WFM の占有帯域 (約 200 kHz) を通せる。 */
    const val FM_IF_SAMPLE_RATE_HZ: Int = 240_000

    /** 出力オーディオのサンプルレート。240 kHz の 1/5 で割り切れる。 */
    const val FM_AUDIO_SAMPLE_RATE_HZ: Int = 48_000

    /** 日本の FM 放送band (ワイド FM 含む) の下限 Hz。 */
    const val FM_BAND_MIN_HZ: Int = 76_000_000

    /** 日本の FM 放送band の上限 Hz。 */
    const val FM_BAND_MAX_HZ: Int = 95_000_000

    /** 日本の FM 放送のチャンネル間隔 Hz。 */
    const val FM_CHANNEL_STEP_HZ: Int = 100_000

    /**
     * FM 放送は信号が強いので最大ゲインは不要。過大入力は前段を飽和させて
     * かえって歪むため、中程度から始めて UI で調整できるようにする。
     */
    const val FM_DEFAULT_TUNER_GAIN_TENTHS_DB: Int = 300
}
