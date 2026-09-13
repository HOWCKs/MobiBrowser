package app.mobibrowser.core

import android.util.Log
import app.mobibrowser.BuildConfig

/**
 * Log único do app. Desligado em builds de release estáveis via [BuildConfig.MOBI_DEBUG_LOGS]
 * (o canal `unstable` do CI mantém ligado de propósito — o feedback vem do logcat do aparelho).
 */
object MobiLog {
    private const val TAG = "MobiBrowser"

    fun d(scope: String, msg: String) {
        if (BuildConfig.MOBI_DEBUG_LOGS) Log.d(TAG, "[$scope] $msg")
    }

    fun i(scope: String, msg: String) {
        Log.i(TAG, "[$scope] $msg")
    }

    fun w(scope: String, msg: String, err: Throwable? = null) {
        Log.w(TAG, "[$scope] $msg", err)
    }

    fun e(scope: String, msg: String, err: Throwable? = null) {
        Log.e(TAG, "[$scope] $msg", err)
    }
}
