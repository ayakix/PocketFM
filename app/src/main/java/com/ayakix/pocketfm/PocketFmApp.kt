package com.ayakix.pocketfm

import android.app.Application
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * プロセス全体で共有するシングルトン。Activity の再生成では死なず、
 * プロセスと運命を共にする。
 *
 * [tuner] をここに置くのは、受信コルーチンを持つ `RadioForegroundService` と
 * 画面を描く `RadioViewModel` が同じ設定値を見る必要があるため。
 *
 * サービス側で起きたエラー (rtl_tcp の接続失敗など) は [errors] 経由で
 * 流し、表示中の Activity がトーストとして拾う。
 */
class PocketFmApp : Application() {

    val tuner: TunerState by lazy { TunerState(this) }

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /** 利用者に見せるべきエラーをサービスから流す。 */
    fun postError(message: String) {
        _errors.tryEmit(message)
    }
}
