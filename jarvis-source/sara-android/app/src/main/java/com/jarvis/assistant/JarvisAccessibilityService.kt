package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "screen not readable"
        val sb = StringBuilder("app: ${root.packageName}\n")
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || sb.length > 4000) return
            val t = n.text?.toString() ?: n.contentDescription?.toString()
            if (!t.isNullOrBlank()) {
                sb.append(if (n.isClickable) "[btn] " else "").append(t.take(120)).append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return sb.toString()
    }

    fun clickText(t: String): String {
        val root = rootInActiveWindow ?: return "screen not readable"
        for (n in root.findAccessibilityNodeInfosByText(t)) {
            var x: AccessibilityNodeInfo? = n
            while (x != null) {
                if (x.isClickable && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "ok clicked"
                x = x.parent
            }
        }
        return "no clickable element with that text"
    }

    fun typeText(t: String): String {
        val root = rootInActiveWindow ?: return "screen not readable"
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return "no focused input field"
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        return if (f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) "ok typed" else "typing failed"
    }

    fun swipe(dir: String): String {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val p = Path()
        when (dir) {
            "up" -> { p.moveTo(w / 2, h * 0.7f); p.lineTo(w / 2, h * 0.3f) }
            "down" -> { p.moveTo(w / 2, h * 0.3f); p.lineTo(w / 2, h * 0.7f) }
            "left" -> { p.moveTo(w * 0.8f, h / 2); p.lineTo(w * 0.2f, h / 2) }
            "right" -> { p.moveTo(w * 0.2f, h / 2); p.lineTo(w * 0.8f, h / 2) }
            else -> return "direction must be up/down/left/right"
        }
        return gesture(p, 300)
    }

    fun tap(x: Int, y: Int): String {
        val p = Path()
        p.moveTo(x.toFloat(), y.toFloat())
        return gesture(p, 50)
    }

    private fun gesture(p: Path, ms: Long): String {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms))
            .build()
        return if (dispatchGesture(g, null, null)) "ok" else "gesture failed"
    }

        val root = rootInActiveWindow ?: return false
        fun search(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            val t = node.text?.toString() ?: ""
            val d = node.contentDescription?.toString() ?: ""
            if (t.contains(query, ignoreCase = true) || d.contains(query, ignoreCase = true)) return node
            for (i in 0 until node.childCount) {
                val found = search(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
        var target = search(root)
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        return target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) ?: false
    
    fun clickByTextOrDesc(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        fun search(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            val t = node.text?.toString() ?: ""
            val d = node.contentDescription?.toString() ?: ""
            if (t.contains(query, ignoreCase = true) || d.contains(query, ignoreCase = true)) return node
            for (i in 0 until node.childCount) {
                val found = search(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
        var target = search(root)
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        return target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    fun typeIntoEditable(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        fun findEditable(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                val found = findEditable(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
        val edit = findEditable(root) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return edit.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}


// البحث عن أي حقل نصي قابل للكتابة والكتابة فيه مباشرة


// النقر على عنصر عبر النص أو contentDescription


        fun search(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {

        fun search(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {

