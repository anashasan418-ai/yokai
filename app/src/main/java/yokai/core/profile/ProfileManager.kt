package yokai.core.profile

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * Mangachi: two fully separate libraries (A and B).
 * Each library has its own database, cover cache and list of custom sites.
 */
object ProfileManager {
    private const val PREFS = "mangachi_profile"
    private const val KEY = "active"

    @Volatile
    private var active: String = "A"

    fun init(context: Context) {
        active = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "A") ?: "A"
    }

    val current: String get() = active

    val isB: Boolean get() = active == "B"

    val dbName: String get() = if (isB) "tachiyomi_b.db" else "tachiyomi.db"

    val dirSuffix: String get() = if (isB) "_b" else ""

    fun switchTo(context: Context, profile: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, profile).commit()
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 300)
    }
}
