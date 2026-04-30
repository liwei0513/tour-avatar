package io.touravatar.util

import android.util.Log

private const val TAG_PREFIX = "TourAvatar"

inline fun <reified T> T.logD(msg: String) =
    Log.d("$TAG_PREFIX/${T::class.java.simpleName}", msg)

inline fun <reified T> T.logI(msg: String) =
    Log.i("$TAG_PREFIX/${T::class.java.simpleName}", msg)

inline fun <reified T> T.logW(msg: String, e: Throwable? = null) =
    Log.w("$TAG_PREFIX/${T::class.java.simpleName}", msg, e)

inline fun <reified T> T.logE(msg: String, e: Throwable? = null) =
    Log.e("$TAG_PREFIX/${T::class.java.simpleName}", msg, e)
