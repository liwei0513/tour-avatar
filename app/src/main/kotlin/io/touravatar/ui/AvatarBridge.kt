package io.touravatar.ui

import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * Bridge that forwards Kotlin → JS commands into the avatar WebView.
 *
 * The WebView's avatar.html exposes `window.TourAvatar = { setEmotion, setSpeaking, wave }`.
 * We call those from Android via [WebView.evaluateJavascript].
 *
 * Reverse channel (JS → Kotlin) is also wired here as @JavascriptInterface
 * so the avatar.html can report ready / errors back to the app.
 */
class AvatarBridge(private val webView: WebView) {

    fun setEmotion(name: String) = run("window.TourAvatar?.setEmotion('$name')")

    fun setSpeaking(active: Boolean) =
        run("window.TourAvatar?.setSpeaking(${if (active) "true" else "false"})")

    fun wave() = run("window.TourAvatar?.wave()")

    private fun run(js: String) {
        webView.post { webView.evaluateJavascript(js, null) }
    }

    /* ----- JS → Kotlin ----- */

    @JavascriptInterface
    fun onAvatarReady() {
        // Hook: useful for hiding a splash or beginning a scripted tour.
    }

    @JavascriptInterface
    fun onAvatarError(message: String) {
        android.util.Log.w("TourAvatar/Bridge", "Avatar JS error: $message")
    }
}
