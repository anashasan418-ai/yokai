package yokai.core.sites

import android.content.Context
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.core.profile.ProfileManager

data class CustomSite(val name: String, val baseUrl: String, val theme: String)

/**
 * Stores the sites the user added by pasting a link. The list is kept separately for each library (A/B).
 */
object CustomSiteManager {

    const val THEME_MADARA = "madara"
    const val THEME_THEMESIA = "themesia"

    private fun prefs(context: Context) =
        context.getSharedPreferences("mangachi_sites" + ProfileManager.dirSuffix, Context.MODE_PRIVATE)

    fun getAll(context: Context): List<CustomSite> {
        val raw = prefs(context).getString("sites", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                CustomSite(o.getString("name"), o.getString("baseUrl"), o.getString("theme"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, sites: List<CustomSite>) {
        val array = JSONArray()
        sites.forEach {
            array.put(JSONObject().put("name", it.name).put("baseUrl", it.baseUrl).put("theme", it.theme))
        }
        prefs(context).edit().putString("sites", array.toString()).apply()
    }

    fun add(context: Context, site: CustomSite) {
        val sites = getAll(context).filterNot { it.baseUrl == site.baseUrl }
        save(context, sites + site)
    }

    fun remove(context: Context, baseUrl: String) {
        save(context, getAll(context).filterNot { it.baseUrl == baseUrl })
    }

    /**
     * Opens the link, finds out which website template it uses, and returns the site (not saved yet).
     * Throws an Exception with a readable message when the site cannot be supported.
     */
    suspend fun detect(rawUrl: String): CustomSite = withContext(Dispatchers.IO) {
        var url = rawUrl.trim()
        if (url.isEmpty()) throw Exception("الرابط فارغ")
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"

        val http = try {
            url.toHttpUrl()
        } catch (e: Exception) {
            throw Exception("الرابط غير صالح")
        }
        val base = http.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')

        val network = Injekt.get<NetworkHelper>()
        val html = try {
            network.client.newCall(GET(base)).execute().use { response ->
                if (!response.isSuccessful) throw Exception("الموقع رفض الاتصال (${response.code})")
                response.body.string()
            }
        } catch (e: Exception) {
            throw Exception(e.message ?: "تعذر فتح الموقع")
        }

        val lower = html.lowercase()
        val theme = when {
            "wp-manga" in lower || "madara" in lower -> THEME_MADARA
            "themesia" in lower || "ts_reader" in lower || "listupd" in lower -> THEME_THEMESIA
            else -> throw Exception("هذا الموقع بتصميم غير مدعوم حالياً")
        }

        val title = Jsoup.parse(html).title()
        val name = title.split(" - ", " – ", " | ", " — ").firstOrNull()?.trim().orEmpty()
            .ifEmpty { http.host }
        CustomSite(name, base, theme)
    }
}
