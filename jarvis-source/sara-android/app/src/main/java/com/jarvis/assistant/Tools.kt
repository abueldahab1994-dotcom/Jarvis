package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject

class Tools(private val ctx: Context) {
    companion object {
        /** Tools that need a spoken "yes" before they run. */
        val SENSITIVE = setOf("send_sms", "call_number", "send_whatsapp")
    }

    private fun def(name: String, desc: String, vararg params: Pair<String, String>): JSONObject {
        val props = JSONObject()
        val req = JSONArray()
        for ((n, t) in params) {
            props.put(n, JSONObject().put("type", t))
            req.put(n)
        }
        val fn = JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", req))
        return JSONObject().put("type", "function").put("function", fn)
    }

    val schema: JSONArray = JSONArray().apply {
        put(def("open_app", "Open an installed app by its name (e.g. WhatsApp, Chrome, Camera)", "name" to "string"))
        put(def("open_url", "Open a web URL in the browser", "url" to "string"))
        put(def("open_settings", "Open a system settings page: wifi, bluetooth, display, sound, battery, location, airplane, main", "page" to "string"))
        put(def("set_volume", "Set media volume 0-100", "percent" to "integer"))
        put(def("set_brightness", "Set screen brightness 0-100", "percent" to "integer"))
        put(def("read_notifications", "Read the most recent notifications"))
        put(def("set_alarm", "Set an alarm for a time of day (24h)", "hour" to "integer", "minute" to "integer", "label" to "string"))
        put(def("flashlight", "Turn the flashlight on or off", "on" to "boolean"))
        put(def("send_sms", "Send an SMS. 'to' is a phone number or a contact name. Needs user confirmation.", "to" to "string", "text" to "string"))
        put(def("call_number", "Call a phone number or contact name. Needs user confirmation.", "to" to "string"))
        put(def("global_action", "System action: home, back, recents, notifications, quick_settings, lock, screenshot", "action" to "string"))
        put(def("read_screen", "Read the text currently visible on the screen"))
        put(def("click_text", "Tap the on-screen element that contains this text", "text" to "string"))
        put(def("type_text", "Type text into the currently focused input field", "text" to "string"))
        put(def("swipe", "Swipe the screen: up, down, left, right", "direction" to "string"))
        put(def("send_whatsapp", "Send a WhatsApp message to a contact name or phone number", "to" to "string", "message" to "string"))
        put(def("tap", "Tap at screen coordinates", "x" to "integer", "y" to "integer"))
    }

    fun describe(name: String, a: JSONObject): String = when (name) {
        "send_sms" -> "ابعت رسالة إلى ${a.optString("to")} تقول ${a.optString("text")}"
        "call_number" -> "اتصل بـ ${a.optString("to")}"
        "send_whatsapp" -> "أبعت رسالة واتساب إلى ${a.optString("to")} نصها: ${a.optString("message")}"
        else -> name
    }

    fun run(name: String, a: JSONObject, confirmed: Boolean): String = try {
        when (name) {
            "open_app" -> openApp(a.getString("name"))
            "open_url" -> { launch(Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("url")))); "ok" }
            "open_settings" -> openSettings(a.getString("page"))
            "set_volume" -> setVolume(a.getInt("percent"))
            "set_brightness" -> setBrightness(a.getInt("percent"))
            "read_notifications" -> readNotifications()
            "set_alarm" -> setAlarm(a.getInt("hour"), a.getInt("minute"), a.optString("label", "Sara"))
            "flashlight" -> flashlight(a.getBoolean("on"))
            "send_sms" -> if (confirmed) sendSms(a.getString("to"), a.getString("text")) else "needs confirmation"
            "call_number" -> if (confirmed) call(a.getString("to")) else "needs confirmation"
            "global_action" -> globalAction(a.getString("action"))
            "read_screen" -> acc { it.readScreen() }
            "click_text" -> acc { it.clickText(a.getString("text")) }
            "type_text" -> acc { it.typeText(a.getString("text")) }
            "swipe" -> acc { it.swipe(a.getString("direction")) }
            "tap" -> acc { it.tap(a.getInt("x"), a.getInt("y")) }
        "send_whatsapp" -> if (confirmed) sendWhatsApp(a.getString("to"), a.getString("message")) else "needs_confirmation"
            else -> "unknown tool: $name"
        }
    } catch (e: Exception) {
        "error: ${e.message}"
    }

    private fun acc(f: (JarvisAccessibilityService) -> String): String {
        val s = JarvisAccessibilityService.instance ?: return "accessibility service is not enabled"
        return f(s)
    }

    /** Start activities through the accessibility service context when possible (background-launch friendly). */
    private fun launch(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = JarvisAccessibilityService.instance ?: ctx
        c.startActivity(i)
    }

    private fun openApp(n: String): String {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val want = n.lowercase().trim()
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == want }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(want) }
            ?: return "app not found: $n"
        launch(Intent().setClassName(hit.activityInfo.packageName, hit.activityInfo.name))
        return "ok opened ${hit.loadLabel(pm)}"
    }

    private fun openSettings(page: String): String {
        val action = when (page) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        launch(Intent(action))
        return "ok opened settings: $page"
    }

    private fun setVolume(p: Int): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, p.coerceIn(0, 100) * max / 100, 0)
        return "ok volume $p%"
    }

    private fun setBrightness(p: Int): String {
        if (!Settings.System.canWrite(ctx)) return "write-settings permission missing; ask the user to grant it in the Sara app"
        val v = p.coerceIn(0, 100) * 255 / 100
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
        return "ok brightness $p%"
    }

    private fun readNotifications(): String {
        val r = JarvisNotificationListener.recent.take(5)
        return if (r.isEmpty()) "no notifications captured (is notification access enabled?)" else r.joinToString("\n")
    }

    private fun setAlarm(h: Int, m: Int, label: String): String {
        launch(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, h)
                .putExtra(AlarmClock.EXTRA_MINUTES, m)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        return "ok alarm set for %02d:%02d".format(h, m)
    }

    private fun flashlight(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "no flashlight found"
        cm.setTorchMode(id, on)
        return "ok flashlight ${if (on) "on" else "off"}"
    }

    private fun resolveNumber(s: String): String {
        if (s.matches(Regex("[+0-9 ()\\-]+"))) return s
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw Exception("contacts permission missing")
        }
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$s%"),
            null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        throw Exception("contact not found: $s")
    }

    @Suppress("DEPRECATION")
    private fun sendSms(to: String, text: String): String {
        if (ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return "SMS permission missing"
        val n = resolveNumber(to)
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sms.sendMultipartTextMessage(n, null, sms.divideMessage(text), null, null)
        return "ok sms sent"
    }

    private fun call(to: String): String {
        if (ctx.checkSelfPermission(android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return "call permission missing"
        launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(resolveNumber(to)))))
        return "ok calling"
    }

    private fun globalAction(n: String): String = acc { s ->
        val code = when (n) {
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return@acc "unknown action"
        }
        if (s.performGlobalAction(code)) "ok" else "action failed"
    }

    private fun sendWhatsApp(to: String, message: String): String {
        val digitsOnly = to.filter { it.isDigit() }
        if (digitsOnly.length >= 8) {
            val url = "https://wa.me/$digitsOnly?text=${android.net.Uri.encode(message)}"
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                ctx.startActivity(intent)
                "opened_whatsapp_chat_for_$digitsOnly"
            } catch (_: Exception) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "opened_whatsapp_link_for_$digitsOnly"
            }
        }
        val launchIntent = ctx.packageManager.getLaunchIntentForPackage("com.whatsapp")
            ?: return "whatsapp_not_installed"
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(launchIntent)
        val service = JarvisAccessibilityService.instance
            ?: return "whatsapp_opened_enable_accessibility"
        if (!service.clickByTextOrDesc(to)) {
            service.clickByTextOrDesc("Search")
            service.clickByTextOrDesc("بحث")
            service.typeIntoEditable(to)
        }
        service.typeIntoEditable(message)
        service.clickByTextOrDesc("Send")
        service.clickByTextOrDesc("إرسال")
        return "whatsapp_flow_executed_for_$to"
    }

}
