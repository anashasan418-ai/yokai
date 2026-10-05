package yokai.core.sites

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * A generic source for websites the user added by link.
 * Works with the two most common manga website templates (Madara and MangaThemesia).
 */
class CustomSiteSource(private val site: CustomSite) : ParsedHttpSource() {

    override val name: String = site.name

    override val baseUrl: String = site.baseUrl.trimEnd('/')

    override val lang: String = "all"

    override val supportsLatest: Boolean = true

    override val id: Long by lazy {
        val bytes = MessageDigest.getInstance("MD5").digest("mangachi/${baseUrl.lowercase()}".toByteArray())
        (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    private val isMadara get() = site.theme == CustomSiteManager.THEME_MADARA

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().add("Referer", "$baseUrl/")

    override fun getFilterList() = FilterList()

    // Popular

    override fun popularMangaRequest(page: Int): Request =
        if (isMadara) {
            GET("$baseUrl/manga/page/$page/?m_orderby=views", headers)
        } else {
            GET("$baseUrl/manga/?page=$page&order=popular", headers)
        }

    override fun popularMangaSelector(): String = listSelector()

    override fun popularMangaFromElement(element: Element): SManga = listItem(element)

    override fun popularMangaNextPageSelector(): String? = nextPageSelector()

    // Latest

    override fun latestUpdatesRequest(page: Int): Request =
        if (isMadara) {
            GET("$baseUrl/manga/page/$page/?m_orderby=latest", headers)
        } else {
            GET("$baseUrl/manga/?page=$page&order=update", headers)
        }

    override fun latestUpdatesSelector(): String = listSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = listItem(element)

    override fun latestUpdatesNextPageSelector(): String? = nextPageSelector()

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return if (isMadara) {
            GET("$baseUrl/page/$page/?s=$q&post_type=wp-manga", headers)
        } else {
            GET("$baseUrl/page/$page/?s=$q", headers)
        }
    }

    override fun searchMangaSelector(): String =
        if (isMadara) "div.c-tabs-item__content, div.page-item-detail" else listSelector()

    override fun searchMangaFromElement(element: Element): SManga = listItem(element)

    override fun searchMangaNextPageSelector(): String? = nextPageSelector()

    private fun listSelector(): String =
        if (isMadara) "div.page-item-detail" else "div.listupd div.bs div.bsx, div.utao div.uta div.imgu"

    private fun nextPageSelector(): String =
        if (isMadara) {
            "div.nav-previous, nav.navigation-ajax, a.nextpostslink, a.next.page-numbers"
        } else {
            "div.pagination .next, div.hpage .r, a.next.page-numbers"
        }

    private fun listItem(element: Element): SManga {
        val manga = SManga.create()
        val link = element.selectFirst("h3 a, h5 a, .post-title a, .tt a")
            ?: element.selectFirst("a[href]")!!
        manga.setUrlWithoutDomain(link.attr("abs:href"))
        manga.title = link.attr("title").ifBlank { link.text() }.ifBlank {
            element.selectFirst("a[title]")?.attr("title").orEmpty()
        }.trim()
        manga.thumbnail_url = element.selectFirst("img")?.let { imageUrl(it) }
        return manga
    }

    private fun imageUrl(img: Element): String? {
        val attrs = listOf("abs:data-src", "abs:data-lazy-src", "abs:data-cfsrc", "abs:src")
        return attrs.map { img.attr(it) }.firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
    }

    // Details

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        if (isMadara) {
            manga.title = document.selectFirst(".post-title h1, .post-title h3")?.ownText()?.trim().orEmpty()
                .ifEmpty { document.selectFirst(".post-title h1, .post-title h3")?.text().orEmpty() }
            manga.author = document.select("div.author-content a").joinToString { it.text() }.ifBlank { null }
            manga.artist = document.select("div.artist-content a").joinToString { it.text() }.ifBlank { null }
            manga.description = document.selectFirst("div.description-summary div.summary__content, div.manga-excerpt")
                ?.text()
            manga.genre = document.select("div.genres-content a").joinToString { it.text() }.ifBlank { null }
            manga.thumbnail_url = document.selectFirst("div.summary_image img")?.let { imageUrl(it) }
            val status = document.select("div.post-content_item")
                .firstOrNull { it.selectFirst("h5, .summary-heading")?.text()?.contains("Status", true) == true }
                ?.selectFirst("div.summary-content")?.text().orEmpty()
            manga.status = parseStatus(status)
        } else {
            manga.title = document.selectFirst("h1.entry-title")?.text().orEmpty()
            manga.description = document.selectFirst("div.entry-content[itemprop=description], div.entry-content")
                ?.text()
            manga.genre = document.select("span.mgen a, div.seriestugenre a").joinToString { it.text() }
                .ifBlank { null }
            manga.thumbnail_url = document.selectFirst("div.thumb img, div[itemprop=image] img")
                ?.let { imageUrl(it) }
            val infos = document.select("div.imptdt, div.tsinfo div.imptdt")
            manga.status = parseStatus(infos.firstOrNull { it.text().contains("Status", true) }?.selectFirst("i")?.text().orEmpty())
            manga.author = infos.firstOrNull { it.text().contains("Author", true) }?.selectFirst("i")?.text()
            manga.artist = infos.firstOrNull { it.text().contains("Artist", true) }?.selectFirst("i")?.text()
        }
        return manga
    }

    private fun parseStatus(text: String): Int {
        val t = text.lowercase()
        return when {
            "ongoing" in t || "publishing" in t || "مستمر" in t -> SManga.ONGOING
            "complete" in t || "finished" in t || "مكتمل" in t -> SManga.COMPLETED
            "hiatus" in t -> SManga.ON_HIATUS
            "cancel" in t || "drop" in t -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    // Chapters

    override fun chapterListRequest(manga: SManga): Request =
        if (isMadara) {
            POST("$baseUrl${manga.url.trimEnd('/')}/ajax/chapters/", headers, FormBody.Builder().build())
        } else {
            GET("$baseUrl${manga.url}", headers)
        }

    override fun chapterListSelector(): String =
        if (isMadara) "li.wp-manga-chapter" else "div.eplister li, #chapterlist li"

    override fun chapterListParse(response: Response): List<SChapter> {
        var document = response.asJsoup()
        var elements = document.select(chapterListSelector())
        if (elements.isEmpty() && isMadara) {
            // Older Madara sites show the chapters inside the manga page itself
            val pageUrl = response.request.url.toString().removeSuffix("/").removeSuffix("ajax/chapters")
            document = client.newCall(GET(pageUrl, headers)).execute().asJsoup()
            elements = document.select(chapterListSelector())
        }
        return elements.map { chapterFromElement(it) }
    }

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val link = element.selectFirst("a[href]")!!
        chapter.url = link.attr("abs:href").removePrefix(baseUrl).ifEmpty { "/" }
        chapter.name = if (isMadara) {
            link.text().trim()
        } else {
            element.selectFirst("span.chapternum")?.text()?.trim().orEmpty().ifEmpty { link.text().trim() }
        }
        val dateText = if (isMadara) {
            element.selectFirst("span.chapter-release-date")?.text()
        } else {
            element.selectFirst("span.chapterdate")?.text()
        }
        chapter.date_upload = parseDate(dateText)
        return chapter
    }

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val formats = listOf("MMMM dd, yyyy", "MMM dd, yyyy", "dd MMMM yyyy", "yyyy-MM-dd")
        for (f in formats) {
            try {
                return SimpleDateFormat(f, Locale.US).parse(text.trim())?.time ?: continue
            } catch (e: Exception) {
                // try next format
            }
        }
        return 0L
    }

    // Pages

    override fun pageListParse(document: Document): List<Page> {
        if (!isMadara) {
            val script = document.select("script").map { it.data() }.firstOrNull { "ts_reader.run" in it }
            if (script != null) {
                val json = Regex("ts_reader\\.run\\((.*?)\\);", RegexOption.DOT_MATCHES_ALL)
                    .find(script)?.groupValues?.get(1)
                if (json != null) {
                    try {
                        val images = JSONObject(json).getJSONArray("sources").getJSONObject(0).getJSONArray("images")
                        return (0 until images.length()).map { Page(it, document.location(), images.getString(it)) }
                    } catch (e: Exception) {
                        // fall back to the plain image list below
                    }
                }
            }
            return document.select("#readerarea img").mapIndexedNotNull { i, img ->
                imageUrl(img)?.let { Page(i, document.location(), it) }
            }
        }
        return document.select("div.page-break img, li.blocks-gallery-item img, .reading-content img")
            .mapIndexedNotNull { i, img -> imageUrl(img)?.let { Page(i, document.location(), it.trim()) } }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()
}
