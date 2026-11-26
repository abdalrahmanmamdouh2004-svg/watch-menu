package com.example

import android.content.Context
import android.content.Intent

data class LaunchableApp(
    val label: String,
    val packageName: String
)

object AppLauncherHelper {
    fun getLaunchableApps(context: Context): List<LaunchableApp> {
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = pm.queryIntentActivities(intent, 0)
            val list = activities.map {
                val label = it.loadLabel(pm).toString()
                val pkg = it.activityInfo.packageName
                LaunchableApp(label, pkg)
            }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
            list
        } catch (e: Exception) {
            AppLogger.logError("AppLauncherHelper", "getLaunchableApps", e)
            emptyList()
        }
    }
}
