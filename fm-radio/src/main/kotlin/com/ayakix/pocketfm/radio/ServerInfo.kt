package com.ayakix.pocketfm.radio

/**
 * rtl_tcp サーバが 12 バイトの挨拶ヘッダで通知してくる情報。
 */
data class ServerInfo(
    /** チューナ種別の数値表現 (人間向けの名前は [tunerName])。 */
    val tunerType: Int,
    /** チューナが公開しているゲイン段数。 */
    val gainStageCount: Int,
) {

    /** チューナのファミリ名。RTL-SDR Blog V4 は R828D (6) を返す。 */
    val tunerName: String = when (tunerType) {
        0 -> "Unknown"
        1 -> "E4000"
        2 -> "FC0012"
        3 -> "FC0013"
        4 -> "FC2580"
        5 -> "R820T"
        6 -> "R828D"
        else -> "Unknown ($tunerType)"
    }
}
