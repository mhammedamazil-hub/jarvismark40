package com.jarvis.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** One visible, tappable element flattened for the model. */
data class ScreenNode(
    val text: String,
    val clickable: Boolean,
    val editable: Boolean,
    val l: Int, val t: Int, val r: Int, val b: Int
) {
    val cx: Int get() = (l + r) / 2
    val cy: Int get() = (t + b) / 2
}

/**
 * JARVIS's hands + eyes on Android. Reads any screen's text/buttons and can tap,
 * swipe, type, launch apps and issue global actions — in ANY app. This is the
 * sanctioned way to drive a phone (the same API screen-readers use).
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
        private const val TAG = "JarvisA11y"
        /** Package currently in the foreground, and a hook so plugins (e.g. focus) can react. */
        @Volatile var currentForeground: String = ""
        @Volatile var foregroundListener: ((String) -> Unit)? = null
        fun isRunning() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrBlank() && pkg != currentForeground) {
                currentForeground = pkg
                runCatching { foregroundListener?.invoke(pkg) }
            }
        }
    }
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Flatten the visible screen into text + clickable nodes (capped for the model). */
    fun readScreen(maxNodes: Int = 60): List<ScreenNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<ScreenNode>()
        val rect = Rect()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 45 || out.size >= maxNodes) return
            val text = (node.text ?: node.contentDescription ?: "").toString()
            val clickable = node.isClickable
            val editable = node.isEditable
            if ((text.isNotBlank() || clickable || editable) && node.isVisibleToUser) {
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    out.add(ScreenNode(text.take(90), clickable, editable,
                        rect.left, rect.top, rect.right, rect.bottom))
                }
            }
            for (i in 0 until node.childCount) {
                val c = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    private fun gesture(path: Path, duration: Long, onDone: (Boolean) -> Unit) {
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gd = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gd, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) = onDone(true)
            override fun onCancelled(d: GestureDescription?) = onDone(false)
        }, null)
    }

    fun tap(x: Int, y: Int, onDone: (Boolean) -> Unit = {}) {
        gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 60, onDone)
    }

    fun longPress(x: Int, y: Int, onDone: (Boolean) -> Unit = {}) {
        gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 650, onDone)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long = 300, onDone: (Boolean) -> Unit = {}) {
        gesture(Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }, duration, onDone)
    }

    /** Type into the focused field, or the first editable field we can find. */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var target = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null) {
            fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (n.isEditable) return n
                for (i in 0 until n.childCount) {
                    val c = try { n.getChild(i) } catch (e: Exception) { null } ?: continue
                    findEditable(c)?.let { return it }
                }
                return null
            }
            target = findEditable(root)
        }
        target ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Resolve a (partial) app name to a package, or null. Needs QUERY_ALL_PACKAGES. */
    fun resolvePackage(name: String): String? {
        val pm = packageManager
        val pkgs = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (e: Exception) { return null }
        val q = name.trim().lowercase()
        return pkgs.firstOrNull { pm.getApplicationLabel(it).toString().lowercase().contains(q) }?.packageName
            ?: pkgs.firstOrNull { it.packageName.lowercase().contains(q.replace(" ", "")) }?.packageName
    }

    /** Open an app by (partial) name. */
    fun launchApp(name: String): Boolean {
        val pkg = resolvePackage(name) ?: return false
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
}
