package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private val fields = LinkedHashMap<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
        }
        root.addView(TextView(this).apply { text = "J.A.R.V.I.S"; textSize = 26f })
        status = TextView(this)
        root.addView(status)

        root.addView(label("\nالإصدار الأول: أوامر محلية بدون إنترنت"))
        root.addView(label("المحادثة الحرة ونموذج الذكاء الاصطناعي المحلي قيد الدمج في الإصدار التالي."))
        root.addView(field("lang", "لغة التعرف على الصوت", Cfg.LANG))
        root.addView(field("uname", "اسمك", "عماد"))
        root.addView(field("pitch", "نعومة الصوت (1.0 عادي، 1.2 أنعم)", "1.2"))
        root.addView(field("rate", "سرعة الكلام (0.9 أهدى)", "0.92"))
        root.addView(field("stt", "محرك الصوت: auto أو vosk أو android", "auto"))
        root.addView(field("wake", "كلمات تنبيه إضافية (بفاصلة)", ""))
        root.addView(button("حفظ الإعدادات") { save() })

        root.addView(label("\nالصلاحيات (اضغط بالترتيب)"))
        root.addView(button("1) أذونات المايك والرسائل والاتصال والجهات والإشعارات") { askPermissions() })
        root.addView(button("2) تفعيل خدمة إمكانية الوصول (Accessibility)") { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        root.addView(button("3) السماح بقراءة الإشعارات") { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        root.addView(button("4) استثناء من توفير البطارية") {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        root.addView(button("5) الظهور فوق التطبيقات") {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(button("6) تعديل إعدادات النظام (السطوع)") {
            open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        })
        root.addView(button("7) إعدادات التطبيق (التشغيل التلقائي/النوافذ في الخلفية)") {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        root.addView(button("8) تحميل محرك التعرف الصوتي Vosk (يحتاج إنترنت أول مرة)") { downloadModel() })

        root.addView(label("\nالتشغيل"))
        root.addView(button("▶ تشغيل JARVIS") { startJarvis(null) })
        root.addView(button("■ إيقاف") { stopService(Intent(this, JarvisService::class.java)); refresh() })

        val cmd = EditText(this).apply { hint = "اكتب أمرًا مثل: افتح واتساب" }
        root.addView(cmd)
        root.addView(button("إرسال الأمر") {
            val t = cmd.text.toString().trim()
            if (t.isNotEmpty()) { startJarvis(t); cmd.setText("") }
        })

        logView = TextView(this).apply { setPadding(0, 24, 0, 0) }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        Bus.listener = { s -> runOnUiThread { logView.text = s + "\n" + logView.text.toString().take(1500) } }
        refresh()
    }

    override fun onPause() {
        Bus.listener = null
        super.onPause()
    }

    private fun label(t: String) = TextView(this).apply { text = t; textSize = 16f }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    private fun field(key: String, hint: String, def: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(prefs.getString(key, def))
        e.setSingleLine(true)
        fields[key] = e
        return e
    }

    private fun save() {
        val ed = prefs.edit()
        for ((k, v) in fields) ed.putString(k, v.text.toString().trim())
        ed.apply()
        Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: Exception) { Toast.makeText(this, "مش قادر أفتح الشاشة دي: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun askPermissions() {
        val p = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(p.toTypedArray(), 1)
    }

    private fun startJarvis(text: String?) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "اضغط زر الأذونات الأول", Toast.LENGTH_LONG).show()
            return
        }
        save()
        val i = Intent(this, JarvisService::class.java)
        if (text != null) i.setAction(JarvisService.ACTION_TEXT).putExtra("text", text)
        startForegroundService(i)
        refresh()
    }

    private fun downloadModel() {
        val dest = VoskEngine.modelDir(this)
        val tmp = File(getExternalFilesDir(null), "model.zip")
        Toast.makeText(this, "بدأ التحميل… تابع التقدم في السجل", Toast.LENGTH_LONG).show()
        Thread {
            try {
                val c = URL("https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip").openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(65536)
                        var n = 0
                        var done = 0L
                        var last = 0L
                        while (input.read(buf).also { n = it } >= 0) {
                            out.write(buf, 0, n)
                            done += n
                            if (done - last > 10_000_000L) {
                                last = done
                                Bus.log("⬇️ ${done / 1_000_000}MB / ${total / 1_000_000}MB")
                            }
                        }
                    }
                }
                Bus.log("📦 بفك الضغط…")
                dest.deleteRecursively()
                dest.mkdirs()
                ZipInputStream(tmp.inputStream().buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val rel = e.name.substringAfter('/', "")
                        if (rel.isNotEmpty()) {
                            val f = File(dest, rel)
                            if (!f.canonicalPath.startsWith(dest.canonicalPath)) throw Exception("bad zip entry")
                            if (e.isDirectory) {
                                f.mkdirs()
                            } else {
                                f.parentFile?.mkdirs()
                                f.outputStream().use { o -> z.copyTo(o) }
                            }
                        }
                        z.closeEntry()
                        e = z.nextEntry
                    }
                }
                tmp.delete()
                Bus.log("✅ تم تثبيت موديل Vosk — اضغط إيقاف ثم تشغيل")
            } catch (ex: Exception) {
                Bus.log("⚠️ فشل التحميل: ${ex.message}")
            }
        }.start()
    }

    private fun refresh() {
        val pm = getSystemService(PowerManager::class.java)
        val notif = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        fun mark(b: Boolean) = if (b) "✅" else "❌"
        status.text = "الخدمة: ${mark(JarvisService.isRunning)}   Accessibility: ${mark(JarvisAccessibilityService.instance != null)}\n" +
            "الإشعارات: ${mark(notif)}   البطارية: ${mark(pm.isIgnoringBatteryOptimizations(packageName))}\n" +
            "الظهور فوق التطبيقات: ${mark(Settings.canDrawOverlays(this))}   إعدادات النظام: ${mark(Settings.System.canWrite(this))}\n" +
            "موديل Vosk: ${mark(VoskEngine.available(this))}"
    }
}
