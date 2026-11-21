package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

class JarvisService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_TEXT = "com.jarvis.assistant.TEXT"
        @Volatile var isRunning = false
        private val WAKE = listOf("جارفس", "جارفيس", "jarvis", "jarv is")
    }

    private fun userName(): String = prefs.getString("uname", "عماد").orEmpty().ifBlank { "عماد" }

    private fun systemPrompt(): String =
        "You are JARVIS, an offline Android assistant. Speak Egyptian Arabic, be concise and factual. Never claim an action succeeded unless the device tool confirms it."

    private fun norm(s: String): String = s.lowercase()
        .replace('ة', 'ه').replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي')

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences
    private lateinit var tools: Tools
    private lateinit var offlineRouter: OfflineCommandRouter
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var vosk: VoskEngine? = null
    private var ttsReady = false
    private val history = JSONArray()

    @Volatile private var speaking = false
    @Volatile private var listening = false
    @Volatile private var awakeUntil = 0L
    @Volatile private var pending: Pair<String, JSONObject>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        tools = Tools(this)
        offlineRouter = OfflineCommandRouter(tools)
        val n = buildNotif()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEXT) {
            val t = intent?.getStringExtra("text").orEmpty()
            if (t.isNotBlank()) {
                Bus.log("⌨️ $t")
                process(t)
            }
        } else {
            main.post { startListening() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        recognizer?.destroy()
        vosk?.stop()
        tts?.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildNotif(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis", "JARVIS", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "jarvis")
            .setContentTitle("JARVIS شغّال")
            .setContentText("قول: JARVIS ...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    // ---------- TTS ----------
    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { finishSpeak() }
            override fun onError(utteranceId: String?) { finishSpeak() }
        })
        if (!ttsReady) Bus.log("⚠️ محرك النطق (TTS) مش شغال — هتشوف الردود مكتوبة بس")
    }

    private fun pickFemaleVoice(t: TextToSpeech) {
        try {
            val vs = t.voices.orEmpty().filter { it.locale.language == "ar" }
            val v = vs.firstOrNull { it.name.contains("female", true) }
                ?: vs.firstOrNull { !it.isNetworkConnectionRequired }
            if (v != null) t.voice = v
        } catch (e: Exception) {
            // keep the default voice
        }
    }

    private fun finishSpeak() {
        main.post {
            speaking = false
            awakeUntil = System.currentTimeMillis() + 8000
            startListening()
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        Bus.log("🤖 $text")
        main.post {
            val t = tts
            if (!ttsReady || t == null) {
                finishSpeak()
                return@post
            }
            speaking = true
            listening = false
            recognizer?.cancel()
            vosk?.pause(true)
            val ar = text.any { it in '\u0600'..'\u06FF' }
            val r = t.setLanguage(if (ar) Locale("ar") else Locale.US)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                t.setLanguage(Locale.getDefault())
            }
            t.setPitch(prefs.getString("pitch", "1.2")?.toFloatOrNull() ?: 1.2f)
            t.setSpeechRate(prefs.getString("rate", "0.92")?.toFloatOrNull() ?: 0.92f)
            if (ar) pickFemaleVoice(t)
            if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis") != TextToSpeech.SUCCESS) {
                finishSpeak()
                return@post
            }
            main.postDelayed({ if (speaking) finishSpeak() }, 15000L + text.length * 120L)
        }
    }

    // ---------- Speech recognition ----------
    private val recListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onError(error: Int) {
            listening = false
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                Bus.log("⚠️ إذن المايك ناقص")
                return
            }
            restartSoon(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 400)
        }
        override fun onResults(results: Bundle?) {
            listening = false
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) onHeard(t)
            restartSoon(300)
        }
    }

    private fun useVosk(): Boolean {
        val m = prefs.getString("stt", "auto")
        return m == "vosk" || (m != "android" && VoskEngine.available(this))
    }

    private fun wakeWords(): List<String> {
        val extra = prefs.getString("wake", "").orEmpty().split(',', '،')
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return WAKE + extra
    }

    private fun restartSoon(ms: Long) {
        main.postDelayed({ startListening() }, ms)
    }

    private fun startListening() {
        if (!isRunning || speaking) return
        if (useVosk()) {
            if (!VoskEngine.available(this)) {
                Bus.log("⚠️ موديل Vosk مش متنزّل — اضغط زر تحميل الموديل")
                return
            }
            val v = vosk ?: VoskEngine(this, { t -> main.post { onHeard(t) } }, { m -> Bus.log("⚠️ Vosk: $m") }).also { vosk = it }
            v.start()
            return
        }
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Bus.log("⚠️ خدمة التعرف على الصوت مش متاحة على الجهاز ده — استخدم الكتابة")
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer!!.setRecognitionListener(recListener)
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.getString("lang", Cfg.LANG))
        listening = true
        recognizer!!.startListening(i)
    }

    private fun onHeard(text: String) {
        Bus.log("🎤 $text")
        val now = System.currentTimeMillis()
        val words = wakeWords().map { norm(it) }
        if ("*" in words) {
            process(text.trim())
            return
        }
        val orig = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val toks = orig.map { norm(it) }
        val idx = toks.indexOfFirst { t -> words.any { w -> t == w || t == "يا$w" } }
        val cmd: String
        if (idx >= 0) {
            cmd = orig.drop(idx + 1).joinToString(" ")
            if (cmd.isEmpty()) {
                awakeUntil = now + 8000
                speak("JARVIS معاك. اتفضل، محتاج إيه؟")
                return
            }
        } else if (now < awakeUntil || pending != null) {
            cmd = text.trim()
        } else {
            return
        }
        process(cmd)
    }

    // ---------- Brain ----------
    private fun isYes(s: String): Boolean {
        val low = s.lowercase()
        return listOf("نعم", "ايوه", "أيوه", "ايوة", "أيوة", "اه", "آه", "أكيد", "اكيد", "تمام", "نفذ", "yes", "yeah", "ok", "confirm", "sure")
            .any { low.contains(it) }
    }

    private fun process(cmd: String) {
        worker.execute {
            try {
                val p = pending
                if (p != null) {
                    pending = null
                    if (isYes(cmd)) {
                        val r = tools.run(p.first, p.second, true)
                        speak(if (r.startsWith("ok")) "تم يا ${userName()}، من عيوني" else r)
                    } else {
                        speak("حاضر يا قلبي، لغيته")
                    }
                    return@execute
                }
                reply(cmd)
            } catch (e: Exception) {
                Bus.log("⚠️ ${e.message}")
                speak("حصلت مشكلة: ${e.message?.take(80)}")
            }
        }
    }

    private fun reply(cmd: String) {
        // Version 0.1 is fully offline for the supported command set.
        // Unknown natural-language questions wait for the local LLM integration;
        // no cloud API is called and no internet connection is required to execute these commands.
        val result = offlineRouter.handle(cmd)
        if (result != null) {
            if (result.startsWith("ok")) {
                speak(when {
                    result.contains("opened") -> "تمام، فتحتلك المطلوب"
                    result.contains("flashlight on") -> "الكشاف اشتغل"
                    result.contains("flashlight off") -> "الكشاف اتقفل"
                    result.contains("volume") -> "تم ضبط الصوت"
                    result.contains("alarm") -> "تم ضبط المنبه"
                    else -> "تم تنفيذ الأمر"
                })
            } else {
                speak("مش قادر أنفذ الأمر دلوقتي. $result")
            }
            return
        }
        speak("أنا شغال أوفلاين. أقدر حاليًا أفتح التطبيقات والإعدادات، أتحكم في الصوت والكشاف، وأقرأ الإشعارات والشاشة. فهم الأسئلة الحرة والمحادثة الذكية لسه هيتضافوا مع نموذج الذكاء الاصطناعي المحلي.")
    }

}
