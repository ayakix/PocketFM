package com.ayakix.pocketfm.driver

import android.content.Intent
import android.net.Uri
import com.ayakix.pocketfm.radio.RtlTcpProtocol

/**
 * 外部の「SDR driver」アプリ (`marto.rtl_tcp_andro`) を起動するためのヘルパ。
 * USB ドングルの所有権はこのドライバ側にあり、I/Q を `rtl_tcp` で配信する。
 *
 * ドライバは常駐サーバ **ではない**。ランチャー画面には意図的に開始ボタンが
 * なく、他アプリが `iqsrc://` の VIEW インテントで頼んだときだけ `rtl_tcp`
 * を立ち上げる。インテントを撃たずにポートへ直接繋いでも、ドングルがどれだけ
 * 正常でも connection refused になるだけ。
 *
 * このインテント往復は Android の USB 権限ダイアログを出す手段でもある。
 * 権限はパッケージ名ではなく **uid** 単位で付与されるため、ドライバを
 * 再インストールすると無言で失効する。その復旧もインテント経由でしかできない。
 */
object SdrDriver {

    /**
     * ドライバに渡し、こちらの接続先にも使うループバックアドレス。
     * `localhost` ではなく数値で書くのは、名前解決が `::1` を先に返す一方で
     * ドライバは IPv4 のみを bind することがあり、ドライバの状態とは無関係な
     * connection refused を踏むため。
     */
    const val HOST: String = "127.0.0.1"

    /**
     * ドングルを開いて `rtl_tcp` を配信させるインテント。URI には
     * `rtl_tcp` バイナリのコマンドライン引数と同じ文字列を載せるので、
     * ポートとサンプルレートを要求と一緒に運べる。
     */
    fun openIntent(
        host: String = HOST,
        port: Int = RtlTcpProtocol.DEFAULT_PORT,
        sampleRateHz: Int = RtlTcpProtocol.FM_DEVICE_SAMPLE_RATE_HZ,
    ): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("iqsrc://-a $host -p $port -s $sampleRateHz"),
    )

    /**
     * 結果が OK でないときの理由。ドライバは libusb / librtlsdr の生の
     * 失敗内容をこの extra に入れてくる。これが無いと利用者には
     * 「再生が始まらない」ことしか分からない。
     */
    fun failureMessage(data: Intent?): String =
        data?.getStringExtra(EXTRA_ERROR)
            ?: "SDR ドライバがドングルを開けませんでした (権限拒否またはキャンセル)"

    /** ドライバアプリ自体が入っていないとき。 */
    const val NOT_INSTALLED_MESSAGE: String =
        "SDR ドライバアプリが未インストールです — marto.rtl_tcp_andro が必要です"

    private const val EXTRA_ERROR = "detailed_exception_message"
}
