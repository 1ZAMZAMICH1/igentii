package com.master.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class MasterAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MasterAccessibility"
        var instance: MasterAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Master Accessibility Service connected successfully.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Here we can listen to window state changes or notification highlights if needed
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    /**
     * Dumps the current screen UI nodes recursively into a simplified JSON representation.
     * This JSON is much cheaper to send to Gemini than a bulky screenshot.
     */
    fun dumpScreenHierarchy(): String {
        val rootNode = rootInActiveWindow ?: return "{ \"error\": \"No active window\" }"
        val rootJson = JSONObject()
        val elementsArray = JSONArray()
        
        parseNodeRecursive(rootNode, elementsArray)
        
        rootJson.put("screenWidth", resources.displayMetrics.widthPixels)
        rootJson.put("screenHeight", resources.displayMetrics.heightPixels)
        rootJson.put("elements", elementsArray)
        
        return rootJson.toString()
    }

    private fun parseNodeRecursive(node: AccessibilityNodeInfo?, elements: JSONArray) {
        if (node == null) return

        val text = node.text?.toString() ?: node.contentDescription?.toString()
        val isClickable = node.isClickable
        val isScrollable = node.isScrollable
        val className = node.className?.toString()
        val resourceId = node.viewIdResourceName
        
        // We only care about elements that have text, are clickable, or have a resource ID
        if (!text.isNullOrEmpty() || isClickable || isScrollable || !resourceId.isNullOrEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            
            val boundsJson = JSONObject().apply {
                put("left", rect.left)
                put("top", rect.top)
                put("right", rect.right)
                put("bottom", rect.bottom)
                put("centerX", rect.centerX())
                put("centerY", rect.centerY())
            }

            val element = JSONObject().apply {
                put("text", text ?: "")
                put("clickable", isClickable)
                put("scrollable", isScrollable)
                put("class", className ?: "")
                put("id", resourceId ?: "")
                put("bounds", boundsJson)
            }
            
            elements.put(element)
        }

        for (i in 0 until node.childCount) {
            parseNodeRecursive(node.getChild(i), elements)
        }
    }

    /**
     * Performs a click at specific coordinates (x, y).
     * Used when we can't find a direct clickable node but have coordinates from Gemini.
     */
    fun clickAt(x: Float, y: Float, callback: (Boolean) -> Unit) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gestureBuilder = GestureDescription.Builder()
        // Tap duration 50ms, starts immediately (0ms offset)
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        
        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                callback(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                callback(false)
            }
        }, null)
    }

    /**
     * Performs a swipe gesture (e.g. scroll up, scroll down, swipe left/right, pull notification shade).
     */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long, callback: (Boolean) -> Unit) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        
        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                callback(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                callback(false)
            }
        }, null)
    }
}
