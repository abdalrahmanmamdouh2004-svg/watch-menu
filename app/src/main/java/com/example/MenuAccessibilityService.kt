package com.example

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityEvent

class MenuAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MenuAccessibilityService? = null
            private set

        fun isServiceEnabled(context: Context): Boolean {
            return try {
                val enabledServices = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
                colonSplitter.setString(enabledServices)
                val expectedComponent = "${context.packageName}/${MenuAccessibilityService::class.java.name}"
                val expectedShortComponent = "${context.packageName}/.MenuAccessibilityService"
                while (colonSplitter.hasNext()) {
                    val componentName = colonSplitter.next()
                    if (componentName.equals(expectedComponent, ignoreCase = true) ||
                        componentName.equals(expectedShortComponent, ignoreCase = true)
                    ) {
                        return true
                    }
                }
                false
            } catch (e: Exception) {
                AppLogger.logError("Accessibility", "checkServiceEnabled", e)
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLogger.log("Accessibility", "Service connected and ready for global actions")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event types declared - this service is only used for global actions
    }

    override fun onInterrupt() {
        AppLogger.log("Accessibility", "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        AppLogger.log("Accessibility", "Service destroyed")
    }

    fun executeGlobal(action: Int, actionName: String): Boolean {
        return try {
            val success = performGlobalAction(action)
            AppLogger.log("Accessibility", "performGlobalAction($actionName) -> $success")
            success
        } catch (e: Exception) {
            AppLogger.logError("Accessibility", "performGlobalAction($actionName)", e)
            false
        }
    }
}
