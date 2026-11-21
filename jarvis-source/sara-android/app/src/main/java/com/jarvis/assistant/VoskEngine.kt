package com.jarvis.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

/** Offline speech recognition (works without Google services). Model lives in the app's external files dir. */
class VoskEngine(
    private val ctx: Context,
    private val onText: (String) -> Unit,
    private val onFail: (String) -> Unit
) {
    companion object {
        fun modelDir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "vosk-model")
        fun available(ctx: Context): Boolean {
            val d = modelDir(ctx)
            return d.isDirectory && (d.list()?.isNotEmpty() == true)
        }
    }

    private var model: Model? = null
    private var svc: SpeechService? = null
    @Volatile private var loading = false

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) { emit(hypothesis) }
        override fun onFinalResult(hypothesis: String?) { emit(hypothesis) }
        override fun onError(exception: Exception?) { onFail(exception?.message ?: "vosk error") }
        override fun onTimeout() {}
    }

    private fun emit(json: String?) {
        val t = try { JSONObject(json ?: "{}").optString("text") } catch (e: Exception) { "" }
        if (t.isNotBlank()) onText(t)
    }

    /** Starts listening, or un-pauses if already running. */
    fun start() {
        val s = svc
        if (s != null) {
            s.setPause(false)
            return
        }
        if (loading) return
        loading = true
        Thread {
            try {
                val m = model ?: Model(modelDir(ctx).absolutePath)
                model = m
                Handler(Looper.getMainLooper()).post {
                    try {
                        val r = Recognizer(m, 16000.0f)
                        val sv = SpeechService(r, 16000.0f)
                        svc = sv
                        sv.startListening(listener)
                    } catch (e: Exception) {
                        onFail(e.message ?: "vosk start failed")
                    } finally {
                        loading = false
                    }
                }
            } catch (e: Exception) {
                loading = false
                onFail(e.message ?: "vosk model load failed")
            }
        }.start()
    }

    fun pause(p: Boolean) {
        svc?.setPause(p)
    }

    fun stop() {
        svc?.stop()
        svc?.shutdown()
        svc = null
    }
}
