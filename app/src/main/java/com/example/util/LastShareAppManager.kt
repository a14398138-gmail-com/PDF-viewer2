package com.example.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class LastSharedApp(
    val packageName: String,
    val className: String?,
    val appName: String,
    val iconBitmap: Bitmap?
)

class ShareTargetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        // This receiver is exported (the system must reach it with the chooser
        // result), so validate the intent instead of trusting any sender:
        // only accept our own chooser-callback action, and only record
        // components that are actually installed on the device.
        if (intent.action != LastShareAppManager.ACTION_SHARE_CHOSEN) {
            Log.w("ShareTargetReceiver", "Ignoring broadcast with unexpected action: ${intent.action}")
            return
        }
        try {
            val chosenComponent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
            }

            if (chosenComponent != null) {
                // Throws NameNotFoundException for spoofed/uninstalled packages.
                context.packageManager.getApplicationInfo(chosenComponent.packageName, 0)
                LastShareAppManager.saveLastSharedApp(context, chosenComponent)
            }
        } catch (e: Exception) {
            Log.w("ShareTargetReceiver", "Failed to record chosen component: ${e.message}")
        }
    }
}

object LastShareAppManager {
    private const val PREFS_NAME = "pdf_share_prefs"
    private const val KEY_PACKAGE = "last_share_pkg"
    private const val KEY_CLASS = "last_share_cls"
    private const val KEY_NAME = "last_share_name"
    private const val KEY_HISTORY_JSON = "share_history_json"
    const val ACTION_SHARE_CHOSEN = "com.example.ACTION_SHARE_CHOSEN"
    private const val MAX_HISTORY = 10

    @Volatile
    private var cachedRecentApps: List<LastSharedApp>? = null

    fun saveLastSharedApp(context: Context, componentName: ComponentName) {
        try {
            cachedRecentApps = null
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(componentName.packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            // Save single primary last app
            prefs.edit()
                .putString(KEY_PACKAGE, componentName.packageName)
                .putString(KEY_CLASS, componentName.className)
                .putString(KEY_NAME, label)
                .apply()

            // Update multi-app history
            val existingHistory = getHistoryEntries(context).toMutableList()
            // Remove duplicate if already exists
            existingHistory.removeAll { it.optString("pkg") == componentName.packageName }
            // Add to front
            val newEntry = JSONObject().apply {
                put("pkg", componentName.packageName)
                put("cls", componentName.className ?: "")
                put("name", label)
            }
            existingHistory.add(0, newEntry)

            val jsonArray = JSONArray()
            existingHistory.take(MAX_HISTORY).forEach { jsonArray.put(it) }

            prefs.edit().putString(KEY_HISTORY_JSON, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.w("LastShareAppManager", "Error saving last shared app: ${e.message}")
        }
    }

    private fun getHistoryEntries(context: Context): List<JSONObject> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_HISTORY_JSON, null) ?: return emptyList()
        val list = mutableListOf<JSONObject>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                list.add(array.getJSONObject(i))
            }
        } catch (e: Exception) {
            Log.w("LastShareAppManager", "Failed to parse history: ${e.message}")
        }
        return list
    }

    fun getLastSharedApp(context: Context): LastSharedApp? {
        val recentList = getRecentSharedApps(context)
        return recentList.firstOrNull()
    }

    suspend fun getRecentSharedAppsAsync(context: Context): List<LastSharedApp> = withContext(Dispatchers.IO) {
        getRecentSharedApps(context)
    }

    fun getRecentSharedApps(context: Context): List<LastSharedApp> {
        cachedRecentApps?.let { return it }
        val pm = context.packageManager
        val result = mutableListOf<LastSharedApp>()
        val seenPackages = mutableSetOf<String>()

        // 1. First add from saved history
        val history = getHistoryEntries(context)
        for (item in history) {
            val pkg = item.optString("pkg")
            if (pkg.isBlank() || seenPackages.contains(pkg)) continue
            val cls = item.optString("cls").takeIf { it.isNotBlank() }
            val name = item.optString("name").ifBlank { "アプリ" }

            try {
                val iconDrawable = if (cls != null) {
                    try {
                        pm.getActivityIcon(ComponentName(pkg, cls))
                    } catch (e: Exception) {
                        pm.getApplicationIcon(pkg)
                    }
                } else {
                    pm.getApplicationIcon(pkg)
                }
                val bitmap = drawableToBitmap(iconDrawable)
                result.add(
                    LastSharedApp(
                        packageName = pkg,
                        className = cls,
                        appName = name,
                        iconBitmap = bitmap
                    )
                )
                seenPackages.add(pkg)
            } catch (_: Exception) {
                // Ignore uninstalled apps
            }
        }

        // 2. Also check single last saved app if not yet added
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val singlePkg = prefs.getString(KEY_PACKAGE, null)
        if (singlePkg != null && !seenPackages.contains(singlePkg)) {
            val singleCls = prefs.getString(KEY_CLASS, null)
            val singleName = prefs.getString(KEY_NAME, null) ?: "アプリ"
            try {
                val iconDrawable = if (singleCls != null) {
                    try {
                        pm.getActivityIcon(ComponentName(singlePkg, singleCls))
                    } catch (e: Exception) {
                        pm.getApplicationIcon(singlePkg)
                    }
                } else {
                    pm.getApplicationIcon(singlePkg)
                }
                result.add(
                    LastSharedApp(
                        packageName = singlePkg,
                        className = singleCls,
                        appName = singleName,
                        iconBitmap = drawableToBitmap(iconDrawable)
                    )
                )
                seenPackages.add(singlePkg)
            } catch (_: Exception) {}
        }

        // 3. Complement with system text-sharing apps if list is short
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
            }
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(shareIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(shareIntent, 0)
            }

            for (info in resolveInfos) {
                if (result.size >= 8) break
                val pkg = info.activityInfo.packageName
                // Skip our own app
                if (pkg == context.packageName || seenPackages.contains(pkg)) continue

                val appName = info.loadLabel(pm).toString()
                val iconDrawable = info.loadIcon(pm)
                val bitmap = drawableToBitmap(iconDrawable)

                result.add(
                    LastSharedApp(
                        packageName = pkg,
                        className = info.activityInfo.name,
                        appName = appName,
                        iconBitmap = bitmap
                    )
                )
                seenPackages.add(pkg)
            }
        } catch (e: Exception) {
            Log.w("LastShareAppManager", "Failed to query system share apps: ${e.message}")
        }

        cachedRecentApps = result
        return result
    }

    fun createShareChooserIntent(context: Context, text: String, title: String = "テキストを共有"): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }

        val receiverIntent = Intent(context, ShareTargetReceiver::class.java).apply {
            action = ACTION_SHARE_CHOSEN
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            1001,
            receiverIntent,
            flags
        )

        return Intent.createChooser(sendIntent, title, pendingIntent.intentSender)
    }

    fun directShare(context: Context, text: String, lastApp: LastSharedApp): Boolean {
        return try {
            val directIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                if (!lastApp.className.isNullOrBlank()) {
                    component = ComponentName(lastApp.packageName, lastApp.className)
                } else {
                    setPackage(lastApp.packageName)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(directIntent)
            // Also record it into history
            if (!lastApp.className.isNullOrBlank()) {
                saveLastSharedApp(context, ComponentName(lastApp.packageName, lastApp.className))
            }
            true
        } catch (e: Exception) {
            Log.w("LastShareAppManager", "Direct share failed: ${e.message}")
            false
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
            val bmp = drawable.bitmap
            // Return software copy if hardware config
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bmp.config == Bitmap.Config.HARDWARE) {
                return bmp.copy(Bitmap.Config.ARGB_8888, false)
            }
            return bmp
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
        val bitmap = Bitmap.createBitmap(width.coerceIn(48, 192), height.coerceIn(48, 192), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
