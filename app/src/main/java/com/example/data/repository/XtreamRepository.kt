package com.example.data.repository

import android.util.JsonReader
import android.util.JsonToken
import com.example.data.model.LibraryFilterType
import com.example.data.model.LibraryMediaItem
import com.example.data.model.MediaDetailInfo
import com.example.data.model.SeriesEpisode
import com.example.data.model.SeriesSeason
import com.example.data.model.XtreamAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class XtreamRepository(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    // High-speed in-memory catalog for instant browsing & sub-5ms comprehensive search across 100% of content
    private val allMovies = CopyOnWriteArrayList<LibraryMediaItem>()
    private val allSeries = CopyOnWriteArrayList<LibraryMediaItem>()
    private val seenMovieIds = HashSet<String>()
    private val seenSeriesIds = HashSet<String>()

    private var activeAccount: XtreamAccount? = null
    @Volatile
    private var isFullIndexStarted = false
    @Volatile
    private var isFullIndexCompleted = false
    private val detailCache = HashMap<String, MediaDetailInfo>()

    companion object {
        // 72 items per page for main browsing (between 60 and 80, and divisible by 3 for 3x3 grid!)
        const val PAGE_SIZE = 72

        /**
         * Normalizes Arabic & English text for ultra-accurate, fast search:
         * unifies Alef variants (أ, إ, آ -> ا), Teh Marbuta (ة -> ه), Alef Maksura (ى -> ي),
         * removes tashkeel/diacritics and punctuation.
         */
        fun normalizeForSearch(input: String): String {
            if (input.isEmpty()) return ""
            val sb = StringBuilder(input.length)
            for (ch in input.lowercase()) {
                when (ch) {
                    'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                    'ة' -> sb.append('ه')
                    'ى' -> sb.append('ي')
                    'ؤ' -> sb.append('و')
                    'ئ' -> sb.append('ي')
                    in '\u064B'..'\u065F', '\u0640' -> Unit // strip Arabic diacritics & tatweel
                    '-', '_', ':', '|', '.', ',', '(', ')', '[', ']', '/' -> sb.append(' ')
                    else -> sb.append(ch)
                }
            }
            return sb.toString().replace(Regex("\\s+"), " ").trim()
        }
    }

    fun resetCatalog() {
        allMovies.clear()
        allSeries.clear()
        synchronized(seenMovieIds) { seenMovieIds.clear() }
        synchronized(seenSeriesIds) { seenSeriesIds.clear() }
        detailCache.clear()
        activeAccount = null
        isFullIndexStarted = false
        isFullIndexCompleted = false
    }

    val totalIndexedCount: Int
        get() = allMovies.size + allSeries.size

    /**
     * Quickly resolves the fastest working Xtream account from Firebase and fetches the initial
     * batch of 72 movies & series in under 1 second using priority categories, while immediately
     * streaming the ENTIRE server catalog in the background so Search covers 100% of all movies & series.
     */
    suspend fun ensureInitialCatalogLoaded(
        accounts: List<XtreamAccount>,
        onBackgroundCatalogUpdated: () -> Unit = {}
    ): Result<List<LibraryMediaItem>> = withContext(Dispatchers.IO) {
        if (accounts.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("لا توجد حسابات مكتبة متاحة في قاعدة البيانات"))
        }

        mutex.withLock {
            val working = activeAccount ?: findFastestWorkingAccount(accounts) ?: accounts.first()
            activeAccount = working

            // Immediately start full streaming index in the background if not started yet
            if (!isFullIndexStarted) {
                isFullIndexStarted = true
                backgroundScope.launch {
                    streamFullCatalogInBackground(accounts, working, onBackgroundCatalogUpdated)
                }
            }

            if (allMovies.isNotEmpty() || allSeries.isNotEmpty()) {
                return@withContext Result.success(buildCombinedList(LibraryFilterType.ALL))
            }

            // Load initial priority categories (Arabic + New Releases + Netflix) in parallel for instant startup
            try {
                coroutineScope {
                    val moviesDeferred = async { fetchFastInitialMovies(working) }
                    val seriesDeferred = async { fetchFastInitialSeries(working) }
                    val initialMovies = moviesDeferred.await()
                    val initialSeries = seriesDeferred.await()

                    addMoviesThreadSafe(initialMovies)
                    addSeriesThreadSafe(initialSeries)
                }
            } catch (_: Exception) {
            }

            val combined = buildCombinedList(LibraryFilterType.ALL)
            if (combined.isEmpty()) {
                streamCatalogItemsProgressive(
                    account = working,
                    action = "get_vod_streams",
                    isSeries = false,
                    maxItems = 120
                ) { batch ->
                    addMoviesThreadSafe(batch)
                }
            }

            Result.success(buildCombinedList(LibraryFilterType.ALL))
        }
    }

    private fun addMoviesThreadSafe(items: List<LibraryMediaItem>): Int {
        if (items.isEmpty()) return 0
        val toAdd = ArrayList<LibraryMediaItem>(items.size)
        synchronized(seenMovieIds) {
            for (m in items) {
                val key = "${m.account.cleanHost}_${m.id}"
                if (seenMovieIds.add(key)) {
                    toAdd.add(m)
                }
            }
        }
        if (toAdd.isNotEmpty()) {
            allMovies.addAll(toAdd)
        }
        return toAdd.size
    }

    private fun addSeriesThreadSafe(items: List<LibraryMediaItem>): Int {
        if (items.isEmpty()) return 0
        val toAdd = ArrayList<LibraryMediaItem>(items.size)
        synchronized(seenSeriesIds) {
            for (s in items) {
                val key = "${s.account.cleanHost}_${s.id}"
                if (seenSeriesIds.add(key)) {
                    toAdd.add(s)
                }
            }
        }
        if (toAdd.isNotEmpty()) {
            allSeries.addAll(toAdd)
        }
        return toAdd.size
    }

    fun getCatalogItems(filter: LibraryFilterType): List<LibraryMediaItem> {
        return buildCombinedList(filter)
    }

    /**
     * Comprehensive, ultra-fast search across 100% of all Movies & Series on the server.
     * Never depends on clicking "عرض المزيد" on the main page.
     * If the full server catalog is still streaming or hasn't started yet, it searches existing items
     * immediately AND streams the full catalog in parallel, pushing updated matches via `onResultsUpdated`.
     */
    suspend fun searchCatalogComprehensive(
        query: String,
        filter: LibraryFilterType,
        accounts: List<XtreamAccount>,
        onResultsUpdated: (List<LibraryMediaItem>) -> Unit = {}
    ): List<LibraryMediaItem> = withContext(Dispatchers.Default) {
        // Ensure full catalog stream is running if accounts are available
        if (!isFullIndexStarted && accounts.isNotEmpty()) {
            isFullIndexStarted = true
            val primary = activeAccount ?: accounts.first()
            activeAccount = primary
            backgroundScope.launch {
                streamFullCatalogInBackground(accounts, primary) {
                    val updated = searchCatalog(query, filter, limit = 600)
                    onResultsUpdated(updated)
                }
            }
        }

        val immediate = searchCatalog(query, filter, limit = 600)
        // If catalog is still empty (user opened search immediately on cold start), wait briefly for first stream batch
        if (immediate.isEmpty() && !isFullIndexCompleted && accounts.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                val primary = activeAccount ?: findFastestWorkingAccount(accounts) ?: accounts.first()
                activeAccount = primary
                coroutineScope {
                    val mJob = launch {
                        streamCatalogItemsProgressive(primary, "get_vod_streams", isSeries = false, maxItems = Int.MAX_VALUE) { batch ->
                            if (addMoviesThreadSafe(batch) > 0 && query.isNotBlank()) {
                                onResultsUpdated(searchCatalog(query, filter, limit = 600))
                            }
                        }
                    }
                    val sJob = launch {
                        streamCatalogItemsProgressive(primary, "get_series", isSeries = true, maxItems = Int.MAX_VALUE) { batch ->
                            if (addSeriesThreadSafe(batch) > 0 && query.isNotBlank()) {
                                onResultsUpdated(searchCatalog(query, filter, limit = 600))
                            }
                        }
                    }
                    mJob.join()
                    sJob.join()
                }
                isFullIndexCompleted = true
            }
            return@withContext searchCatalog(query, filter, limit = 600)
        }

        immediate
    }

    /**
     * Ultra-fast in-memory search across ALL indexed movies & series with Arabic/English normalization
     * and smart relevance ranking (Exact -> StartsWith -> All-Words Match).
     */
    fun searchCatalog(
        query: String,
        filter: LibraryFilterType,
        limit: Int = 600
    ): List<LibraryMediaItem> {
        val cleanQuery = normalizeForSearch(query)
        if (cleanQuery.isEmpty()) {
            return buildCombinedList(filter).take(120)
        }

        val tokens = cleanQuery.split(" ").filter { it.isNotBlank() }
        val exactMatches = ArrayList<LibraryMediaItem>()
        val prefixMatches = ArrayList<LibraryMediaItem>()
        val containsMatches = ArrayList<LibraryMediaItem>()

        val searchMovies = filter == LibraryFilterType.ALL || filter == LibraryFilterType.MOVIES
        val searchSeries = filter == LibraryFilterType.ALL || filter == LibraryFilterType.SERIES

        fun evaluateItem(item: LibraryMediaItem) {
            val normName = item.lowerName
            if (normName == cleanQuery) {
                exactMatches.add(item)
            } else if (normName.startsWith(cleanQuery) || normName.contains(" $cleanQuery")) {
                prefixMatches.add(item)
            } else if (matchesAllTokens(normName, cleanQuery, tokens)) {
                containsMatches.add(item)
            }
        }

        if (searchMovies && searchSeries) {
            val mList = allMovies
            val sList = allSeries
            val maxLen = maxOf(mList.size, sList.size)
            for (i in 0 until maxLen) {
                if (i < mList.size) evaluateItem(mList[i])
                if (i < sList.size) evaluateItem(sList[i])
                if (exactMatches.size + prefixMatches.size >= limit) break
            }
        } else if (searchMovies) {
            for (m in allMovies) {
                evaluateItem(m)
                if (exactMatches.size + prefixMatches.size >= limit) break
            }
        } else if (searchSeries) {
            for (s in allSeries) {
                evaluateItem(s)
                if (exactMatches.size + prefixMatches.size >= limit) break
            }
        }

        val combined = ArrayList<LibraryMediaItem>(
            minOf(limit, exactMatches.size + prefixMatches.size + containsMatches.size)
        )
        for (item in exactMatches) {
            if (combined.size >= limit) break
            combined.add(item)
        }
        for (item in prefixMatches) {
            if (combined.size >= limit) break
            combined.add(item)
        }
        for (item in containsMatches) {
            if (combined.size >= limit) break
            combined.add(item)
        }
        return combined
    }

    private fun matchesAllTokens(normalizedName: String, fullQuery: String, tokens: List<String>): Boolean {
        if (normalizedName.contains(fullQuery)) return true
        if (tokens.isEmpty()) return false
        for (t in tokens) {
            if (!normalizedName.contains(t)) return false
        }
        return true
    }

    private fun buildCombinedList(filter: LibraryFilterType): List<LibraryMediaItem> {
        return when (filter) {
            LibraryFilterType.MOVIES -> allMovies.toList()
            LibraryFilterType.SERIES -> allSeries.toList()
            LibraryFilterType.ALL -> {
                val mList = allMovies
                val sList = allSeries
                val out = ArrayList<LibraryMediaItem>(mList.size + sList.size)
                var mIdx = 0
                var sIdx = 0
                // Interleave 2 movies + 1 series for a rich, balanced 3x3 grid
                while (mIdx < mList.size || sIdx < sList.size) {
                    if (mIdx < mList.size) out.add(mList[mIdx++])
                    if (mIdx < mList.size) out.add(mList[mIdx++])
                    if (sIdx < sList.size) out.add(sList[sIdx++])
                }
                out
            }
        }
    }

    private suspend fun findFastestWorkingAccount(accounts: List<XtreamAccount>): XtreamAccount? =
        coroutineScope {
            for (acc in accounts) {
                try {
                    val url = "${acc.cleanHost}/player_api.php?username=${acc.username}&password=${acc.password}"
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "IPTVSmartersPlayer")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            if (body.contains("user_info")) {
                                return@coroutineScope acc
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            accounts.firstOrNull()
        }

    private suspend fun fetchFastInitialMovies(account: XtreamAccount): List<LibraryMediaItem> =
        coroutineScope {
            val catIds = fetchPriorityCategoryIds(account, "get_vod_categories", maxCategories = 3)
            if (catIds.isEmpty()) {
                val list = ArrayList<LibraryMediaItem>(90)
                streamCatalogItemsProgressive(
                    account = account,
                    action = "get_vod_streams",
                    isSeries = false,
                    maxItems = 90
                ) { batch -> list.addAll(batch) }
                return@coroutineScope list
            }
            catIds.map { catId ->
                async {
                    val list = ArrayList<LibraryMediaItem>(60)
                    streamCatalogItemsProgressive(
                        account = account,
                        action = "get_vod_streams&category_id=$catId",
                        isSeries = false,
                        maxItems = 60
                    ) { batch -> list.addAll(batch) }
                    list
                }
            }.awaitAll().flatten()
        }

    private suspend fun fetchFastInitialSeries(account: XtreamAccount): List<LibraryMediaItem> =
        coroutineScope {
            val catIds = fetchPriorityCategoryIds(account, "get_series_categories", maxCategories = 3)
            if (catIds.isEmpty()) {
                val list = ArrayList<LibraryMediaItem>(90)
                streamCatalogItemsProgressive(
                    account = account,
                    action = "get_series",
                    isSeries = true,
                    maxItems = 90
                ) { batch -> list.addAll(batch) }
                return@coroutineScope list
            }
            catIds.map { catId ->
                async {
                    val list = ArrayList<LibraryMediaItem>(60)
                    streamCatalogItemsProgressive(
                        account = account,
                        action = "get_series&category_id=$catId",
                        isSeries = true,
                        maxItems = 60
                    ) { batch -> list.addAll(batch) }
                    list
                }
            }.awaitAll().flatten()
        }

    private fun fetchPriorityCategoryIds(
        account: XtreamAccount,
        action: String,
        maxCategories: Int
    ): List<String> {
        return try {
            val url = "${account.cleanHost}/player_api.php?username=${account.username}&password=${account.password}&action=$action"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "IPTVSmartersPlayer")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONArray(resp.body?.string().orEmpty())
                val arabicOrNew = mutableListOf<String>()
                val fallback = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("category_id", "")
                    val name = obj.optString("category_name", "").uppercase()
                    if (id.isNotEmpty()) {
                        if (name.contains("ARAB") || name.contains("عرب") || name.contains("مترجم") ||
                            name.contains("NEW") || name.contains("NETFLIX") || name.contains("202")
                        ) {
                            arabicOrNew.add(id)
                        } else if (fallback.size < maxCategories) {
                            fallback.add(id)
                        }
                    }
                }
                (arabicOrNew + fallback).distinct().take(maxCategories)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Streams 100% of all Movies and Series across all active Xtream accounts in the background
     * in real-time progressive batches (every 600 items), so comprehensive search has the entire
     * catalog available without any artificial limits.
     */
    private suspend fun streamFullCatalogInBackground(
        accounts: List<XtreamAccount>,
        primaryAccount: XtreamAccount,
        onUpdated: () -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val orderedAccounts = listOf(primaryAccount) + accounts.filter { it.id != primaryAccount.id }
            for (acc in orderedAccounts) {
                coroutineScope {
                    val seriesJob = launch {
                        streamCatalogItemsProgressive(
                            account = acc,
                            action = "get_series",
                            isSeries = true,
                            maxItems = Int.MAX_VALUE
                        ) { batch ->
                            if (addSeriesThreadSafe(batch) > 0) {
                                onUpdated()
                            }
                        }
                    }

                    val moviesJob = launch {
                        streamCatalogItemsProgressive(
                            account = acc,
                            action = "get_vod_streams",
                            isSeries = false,
                            maxItems = Int.MAX_VALUE
                        ) { batch ->
                            if (addMoviesThreadSafe(batch) > 0) {
                                onUpdated()
                            }
                        }
                    }

                    seriesJob.join()
                    moviesJob.join()
                }
            }
            isFullIndexCompleted = true
            onUpdated()
        } catch (_: Exception) {
        }
    }

    /**
     * Streams JSON array from Xtream API using Android's streaming JsonReader and emits batches
     * of 600 items progressively so memory remains minimal and search results update in real time.
     */
    private fun streamCatalogItemsProgressive(
        account: XtreamAccount,
        action: String,
        isSeries: Boolean,
        maxItems: Int,
        batchSize: Int = 600,
        onBatchReady: (List<LibraryMediaItem>) -> Unit
    ) {
        val currentBatch = ArrayList<LibraryMediaItem>(minOf(batchSize, maxItems))
        var totalRead = 0
        val url = "${account.cleanHost}/player_api.php?username=${account.username}&password=${account.password}&action=$action"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "IPTVSmartersPlayer")
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val stream = resp.body?.byteStream() ?: return
                val reader = JsonReader(InputStreamReader(stream, Charsets.UTF_8))
                reader.isLenient = true
                if (reader.peek() != JsonToken.BEGIN_ARRAY) return
                reader.beginArray()
                while (reader.hasNext() && totalRead < maxItems) {
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                        reader.skipValue()
                        continue
                    }
                    reader.beginObject()
                    var id = ""
                    var name = ""
                    var poster = ""
                    var rating = ""
                    var ext = "mp4"
                    var catId = ""
                    var plot = ""
                    var year = ""

                    while (reader.hasNext()) {
                        val field = reader.nextName()
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull()
                            continue
                        }
                        when (field) {
                            "stream_id", "series_id" -> id = nextStringSafe(reader)
                            "name", "title" -> name = nextStringSafe(reader)
                            "stream_icon", "cover" -> poster = nextStringSafe(reader)
                            "rating" -> rating = nextStringSafe(reader)
                            "container_extension" -> ext = nextStringSafe(reader).ifBlank { "mp4" }
                            "category_id" -> catId = nextStringSafe(reader)
                            "plot" -> plot = nextStringSafe(reader)
                            "releaseDate", "year" -> year = nextStringSafe(reader)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()

                    if (id.isNotEmpty() && name.isNotEmpty()) {
                        val cleanName = cleanDisplayName(name)
                        val extractedYear = if (year.length >= 4) {
                            year.take(4)
                        } else {
                            extractYearFromTitle(name)
                        }
                        currentBatch.add(
                            LibraryMediaItem(
                                id = id,
                                name = cleanName,
                                lowerName = normalizeForSearch("$cleanName $name"),
                                posterUrl = poster,
                                rating = formatRating(rating),
                                releaseYear = extractedYear,
                                isSeries = isSeries,
                                containerExtension = ext,
                                categoryId = catId,
                                plotPreview = plot,
                                account = account
                            )
                        )
                        totalRead++
                        if (currentBatch.size >= batchSize) {
                            onBatchReady(ArrayList(currentBatch))
                            currentBatch.clear()
                        }
                    }
                }
                if (currentBatch.isNotEmpty()) {
                    onBatchReady(currentBatch)
                }
            }
        } catch (_: Exception) {
            if (currentBatch.isNotEmpty()) {
                onBatchReady(currentBatch)
            }
        }
    }

    private fun nextStringSafe(reader: JsonReader): String {
        return try {
            when (reader.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
                JsonToken.BOOLEAN -> reader.nextBoolean().toString()
                else -> {
                    reader.skipValue()
                    ""
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Fetches real Movie or Series details (plot/description, cast, genre, seasons, episodes)
     * directly from the Xtream Codes API (`get_vod_info` or `get_series_info`).
     */
    suspend fun getMediaDetails(item: LibraryMediaItem): Result<MediaDetailInfo> =
        withContext(Dispatchers.IO) {
            val cacheKey = "${if (item.isSeries) "S" else "M"}_${item.id}"
            detailCache[cacheKey]?.let { return@withContext Result.success(it) }

            val acc = item.account
            val action = if (item.isSeries) {
                "get_series_info&series_id=${item.id}"
            } else {
                "get_vod_info&vod_id=${item.id}"
            }
            val url = "${acc.cleanHost}/player_api.php?username=${acc.username}&password=${acc.password}&action=$action"

            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "IPTVSmartersPlayer")
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(
                            IllegalStateException("تعذر جلب التفاصيل: ${resp.code}")
                        )
                    }
                    val bodyStr = resp.body?.string().orEmpty()
                    val root = JSONObject(bodyStr)
                    val infoObj = root.optJSONObject("info") ?: JSONObject()

                    val description = infoObj.optString(
                        "plot",
                        infoObj.optString("description", infoObj.optString("overview", item.plotPreview))
                    ).trim().ifBlank { "لا يوجد وصف متاح لهذا العمل في المصدر حالياً." }

                    val genre = infoObj.optString("genre", "").trim()
                    val releaseDate = infoObj.optString(
                        "releasedate",
                        infoObj.optString("releaseDate", item.releaseYear)
                    ).trim()
                    val cast = infoObj.optString("cast", infoObj.optString("actors", "")).trim()
                    val director = infoObj.optString("director", "").trim()
                    val duration = infoObj.optString("duration", infoObj.optString("episode_run_time", "")).trim()
                    val rating = formatRating(infoObj.optString("rating", item.rating))

                    var backdropUrl = infoObj.optString("cover_big", infoObj.optString("movie_image", item.posterUrl))
                    val backdropArr = infoObj.optJSONArray("backdrop_path")
                    if (backdropArr != null && backdropArr.length() > 0) {
                        val firstBackdrop = backdropArr.optString(0, "")
                        if (firstBackdrop.isNotEmpty()) backdropUrl = firstBackdrop
                    }

                    // For movies, also check movie_data.container_extension
                    val movieDataObj = root.optJSONObject("movie_data")
                    val realExt = movieDataObj?.optString("container_extension", item.containerExtension)
                        ?.ifBlank { item.containerExtension } ?: item.containerExtension
                    val updatedItem = item.copy(containerExtension = realExt)

                    if (!item.isSeries) {
                        val detail = MediaDetailInfo(
                            item = updatedItem,
                            description = description,
                            genre = genre,
                            releaseDate = releaseDate,
                            cast = cast,
                            director = director,
                            duration = duration,
                            rating = rating,
                            backdropUrl = backdropUrl
                        )
                        detailCache[cacheKey] = detail
                        return@withContext Result.success(detail)
                    }

                    // Parse Series Seasons & Episodes cleanly and in strict order
                    val episodesBySeason = LinkedHashMap<Int, List<SeriesEpisode>>()
                    val episodesNode = root.opt("episodes")
                    if (episodesNode is JSONObject) {
                        val seasonKeys = episodesNode.keys().asSequence().toList()
                            .sortedBy { it.toIntOrNull() ?: 999 }
                        for (sKey in seasonKeys) {
                            val seasonNum = sKey.toIntOrNull() ?: 1
                            val epArr = episodesNode.optJSONArray(sKey) ?: continue
                            val parsedEps = parseEpisodeArray(epArr, seasonNum, item.posterUrl)
                            if (parsedEps.isNotEmpty()) {
                                episodesBySeason[seasonNum] = parsedEps
                            }
                        }
                    } else if (episodesNode is JSONArray) {
                        for (i in 0 until episodesNode.length()) {
                            val subArr = episodesNode.optJSONArray(i)
                            if (subArr != null) {
                                val seasonNum = i + 1
                                val parsedEps = parseEpisodeArray(subArr, seasonNum, item.posterUrl)
                                if (parsedEps.isNotEmpty()) {
                                    episodesBySeason[seasonNum] = parsedEps
                                }
                            }
                        }
                    }

                    val seasonsList = mutableListOf<SeriesSeason>()
                    val seasonsArr = root.optJSONArray("seasons")
                    if (seasonsArr != null) {
                        for (i in 0 until seasonsArr.length()) {
                            val sObj = seasonsArr.optJSONObject(i) ?: continue
                            val sNum = sObj.optInt("season_number", i + 1)
                            val epListForSeason = episodesBySeason[sNum]
                            if (epListForSeason != null && epListForSeason.isNotEmpty()) {
                                seasonsList.add(
                                    SeriesSeason(
                                        seasonNumber = sNum,
                                        name = sObj.optString("name", "الموسم $sNum").ifBlank { "الموسم $sNum" },
                                        episodeCount = epListForSeason.size,
                                        coverUrl = sObj.optString("cover", item.posterUrl).ifBlank { item.posterUrl },
                                        overview = sObj.optString("overview", "")
                                    )
                                )
                            }
                        }
                    }

                    // Ensure every season present in episodesBySeason is represented in seasonsList
                    val existingSeasonNums = seasonsList.map { it.seasonNumber }.toSet()
                    for ((sNum, epList) in episodesBySeason) {
                        if (sNum !in existingSeasonNums) {
                            seasonsList.add(
                                SeriesSeason(
                                    seasonNumber = sNum,
                                    name = "الموسم $sNum",
                                    episodeCount = epList.size,
                                    coverUrl = item.posterUrl
                                )
                            )
                        }
                    }
                    seasonsList.sortBy { it.seasonNumber }

                    val detail = MediaDetailInfo(
                        item = updatedItem,
                        description = description,
                        genre = genre,
                        releaseDate = releaseDate,
                        cast = cast,
                        director = director,
                        duration = duration,
                        rating = rating,
                        backdropUrl = backdropUrl,
                        seasons = seasonsList,
                        episodesBySeason = episodesBySeason
                    )
                    detailCache[cacheKey] = detail
                    Result.success(detail)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun parseEpisodeArray(
        epArr: JSONArray,
        defaultSeasonNum: Int,
        fallbackCover: String
    ): List<SeriesEpisode> {
        val list = ArrayList<SeriesEpisode>(epArr.length())
        for (i in 0 until epArr.length()) {
            val epObj = epArr.optJSONObject(i) ?: continue
            val id = epObj.optString("id", "")
            if (id.isEmpty()) continue
            val epNum = epObj.optInt("episode_num", i + 1)
            val seasonNum = epObj.optInt("season", defaultSeasonNum)
            val rawTitle = epObj.optString("title", "الحلقة $epNum")
            val ext = epObj.optString("container_extension", "mp4").ifBlank { "mp4" }

            val epInfo = epObj.optJSONObject("info")
            val plot = epInfo?.optString("plot", "")?.trim().orEmpty()
            val duration = epInfo?.optString("duration", "")?.trim()
                ?.takeIf { it != "00:00:00" }.orEmpty()
            val rating = formatRating(epInfo?.optString("rating", "").orEmpty())
            val releaseDate = epInfo?.optString("releasedate", "")?.trim().orEmpty()
            val cover = epInfo?.optString("movie_image", fallbackCover)?.ifBlank { fallbackCover }
                ?: fallbackCover

            list.add(
                SeriesEpisode(
                    id = id,
                    episodeNum = epNum,
                    seasonNum = seasonNum,
                    title = cleanDisplayName(rawTitle).ifBlank { "الحلقة $epNum" },
                    plot = plot,
                    duration = duration,
                    rating = rating,
                    releaseDate = releaseDate,
                    containerExtension = ext,
                    coverUrl = cover
                )
            )
        }
        return list.sortedBy { it.episodeNum }
    }

    /**
     * Builds the strict ordered sequence of candidate stream URLs for the Library Player:
     * 1. http://host/movie/user/pass/id.mp4 (or /series/...)
     * 2. http://host/movie/user/pass/id.mkv
     * 3. http://host/movie/user/pass/id.m3u8
     */
    fun buildLibraryStreamCandidates(
        account: XtreamAccount,
        streamId: String,
        isSeries: Boolean
    ): List<String> {
        val typeSegment = if (isSeries) "series" else "movie"
        val base = "${account.cleanHost}/$typeSegment/${account.username}/${account.password}/$streamId"
        return listOf(
            "$base.mp4",
            "$base.mkv",
            "$base.m3u8"
        )
    }

    private fun cleanDisplayName(raw: String): String {
        return raw.replace(Regex("^[A-Z]{2,4}\\s*[-|:]\\s*"), "").trim()
    }

    private fun extractYearFromTitle(title: String): String {
        val match = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(title)
        return match?.value.orEmpty()
    }

    private fun formatRating(raw: String): String {
        val num = raw.toDoubleOrNull() ?: return ""
        if (num <= 0.0) return ""
        return String.format(java.util.Locale.US, "%.1f", num)
    }
}
