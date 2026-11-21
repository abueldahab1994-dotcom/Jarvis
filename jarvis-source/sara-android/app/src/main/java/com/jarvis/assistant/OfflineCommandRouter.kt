package com.jarvis.assistant

import org.json.JSONObject

/**
 * First offline brain: deterministic Egyptian-Arabic command routing.
 * This is intentionally NOT presented as a local LLM. It provides reliable
 * device actions while the on-device language model is integrated separately.
 */
class OfflineCommandRouter(private val tools: Tools) {
    private fun norm(s: String): String = s.lowercase()
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ة', 'ه').replace('ى', 'ي')
        .replace("؟", " ").replace("،", " ").trim()

    fun handle(input: String): String? {
        val s = norm(input)
        if (s.isBlank()) return null

        // Device controls are checked before generic "open/run" phrases.
        if (s.contains("شغل الكشاف") || s.contains("افتح الكشاف") || s.contains("نور الفلاش"))
            return run("flashlight", JSONObject().put("on", true))
        if (s.contains("اقفل الكشاف") || s.contains("اطفي الكشاف") || s.contains("اقفل الفلاش") || s.contains("اطفي الفلاش"))
            return run("flashlight", JSONObject().put("on", false))

        val volume = Regex("(?:خلي|ظبط|علي|وطي|قلل).*?(?:الصوت|الفوليوم).*?(\\d{1,3})")
            .find(s)?.groupValues?.get(1)
            ?: Regex("(?:الصوت|الفوليوم).*?(\\d{1,3})").find(s)?.groupValues?.get(1)
        if (volume != null) return run("set_volume", JSONObject().put("percent", volume.toInt().coerceIn(0, 100)))
        if (s.contains("علي الصوت للاخر")) return run("set_volume", JSONObject().put("percent", 100))
        if (s.contains("وطي الصوت للاخر") || s.contains("اقفل الصوت")) return run("set_volume", JSONObject().put("percent", 0))

        if (listOf("افتح الاعدادات", "افتح اعدادات الموبايل", "روح للاعدادات").any { s.contains(it) })
            return run("open_settings", JSONObject().put("page", "main"))
        if (s.contains("افتح الواي فاي") || s.contains("اعدادات الواي فاي"))
            return run("open_settings", JSONObject().put("page", "wifi"))
        if (s.contains("افتح البلوتوث") || s.contains("اعدادات البلوتوث"))
            return run("open_settings", JSONObject().put("page", "bluetooth"))
        if (s.contains("افتح الصوت") || s.contains("اعدادات الصوت"))
            return run("open_settings", JSONObject().put("page", "sound"))
        if (s.contains("افتح الشاشه") || s.contains("اعدادات الشاشه"))
            return run("open_settings", JSONObject().put("page", "display"))

        if (s.contains("اقرا الاشعارات") || s.contains("في اشعارات"))
            return run("read_notifications", JSONObject())
        if (s.contains("اقرا الشاشه") || s.contains("ايه اللي على الشاشه"))
            return run("read_screen", JSONObject())
        if (s == "ارجع" || s == "رجوع" || s.contains("ارجع للخلف"))
            return run("global_action", JSONObject().put("action", "back"))
        if (s == "الرئيسيه" || s == "روح للرئيسيه" || s == "الشاشه الرئيسيه")
            return run("global_action", JSONObject().put("action", "home"))
        if (s.contains("افتح الاشعارات"))
            return run("global_action", JSONObject().put("action", "notifications"))
        if (s.contains("الاعدادات السريعه"))
            return run("global_action", JSONObject().put("action", "quick_settings"))

        // Open installed apps by common Egyptian Arabic phrasing.
        val openPrefixes = listOf("افتحلي ", "افتح ", "شغل ", "شغللي ", "روح على ", "ادخل على ")
        for (prefix in openPrefixes) {
            if (s.startsWith(prefix)) {
                val target = s.removePrefix(prefix).trim()
                if (target.isNotBlank()) {
                    val app = when {
                        target.contains("واتس") -> "WhatsApp"
                        target.contains("فيس") -> "Facebook"
                        target.contains("انستا") -> "Instagram"
                        target.contains("يوتيوب") -> "YouTube"
                        target.contains("كروم") -> "Chrome"
                        target.contains("كاميرا") -> "Camera"
                        target.contains("اعدادات") -> null
                        else -> target
                    }
                    if (app == null) return run("open_settings", JSONObject().put("page", "main"))
                    return run("open_app", JSONObject().put("name", app))
                }
            }
        }
        return null
    }

    private fun run(name: String, args: JSONObject): String = tools.run(name, args, false)
}
