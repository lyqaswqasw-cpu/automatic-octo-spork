package com.example.data.model

import java.util.Locale

enum class BottomNavTab {
    HOME,
    LIBRARY,
    MATCHES,
    SETTINGS
}

enum class LibraryFilterType(val labelAr: String) {
    ALL("الكل"),
    MOVIES("الأفلام"),
    SERIES("المسلسلات")
}

// --- Firebase Realtime Database Models ---

/**
 * Represents a Branch (فرع رئيسي) or Sub-Branch (فرع داخل فرع) on the Home screen.
 */
data class ChannelCategory(
    val id: String,
    val name: String,
    val imageUrl: String = "",
    val parentId: String = "",
    val order: Int = 0
)

data class ChannelServer(
    val id: String,
    val name: String,
    val streamType: String, // "m3u8" or "ts"
    val streamUrl: String
)

data class LiveChannel(
    val id: String,
    val name: String,
    val category: String,
    val branchId: String = "",
    val imageUrl: String,
    val servers: List<ChannelServer>,
    val views: Int = 0
)

data class EventBanner(
    val id: String,
    val title: String,
    val imageUrl: String,
    val channelId: String,
    val channelName: String,
    val channelIds: List<String>,
    val channelNames: List<String>,
    val showWatchNow: Boolean,
    val watchNowUrl: String
)

data class LiveEventMatch(
    val id: String,
    val name: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeLogo: String,
    val imageUrl: String,
    val broadcaster: String,
    val leagueName: String,
    val isLive: Boolean,
    val targetChannelId: String
)

data class XtreamAccount(
    val id: String,
    val name: String,
    val host: String,
    val username: String,
    val password: String,
    val streamExtension: String = "mp4"
) {
    val cleanHost: String
        get() = host.trim().removeSuffix("/")
}

data class AppVersionConfig(
    val requiredVersion: String = APP_CURRENT_VERSION,
    val updateText: String = "يرجى تحديث التطبيق إلى الإصدار الجديد للاستمرار في المشاهدة.",
    val updateUrl: String = "",
    val telegramUrl: String = "https://t.me/jdj_q",
    val lockOldVersions: Boolean = true
) {
    val isForcedUpdateLocked: Boolean
        get() {
            val cleanReq = requiredVersion.trim()
            if (cleanReq.isEmpty()) return false
            return lockOldVersions && cleanReq != APP_CURRENT_VERSION && cleanReq != "1.0.0"
        }

    companion object {
        const val APP_CURRENT_VERSION = "1.0"
    }
}

data class FirebaseHomePayload(
    val categories: List<ChannelCategory>,
    val channels: List<LiveChannel>,
    val banners: List<EventBanner>,
    val liveMatches: List<LiveEventMatch>,
    val xtreamAccounts: List<XtreamAccount>,
    val telegramUrl: String,
    val versionConfig: AppVersionConfig
)

// --- Library (Xtream Codes) Models ---

data class LibraryMediaItem(
    val id: String,
    val name: String,
    val lowerName: String,
    val posterUrl: String,
    val rating: String,
    val releaseYear: String,
    val isSeries: Boolean,
    val containerExtension: String,
    val categoryId: String,
    val plotPreview: String = "",
    val account: XtreamAccount
)

data class SeriesSeason(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int,
    val coverUrl: String,
    val overview: String = ""
)

data class SeriesEpisode(
    val id: String,
    val episodeNum: Int,
    val seasonNum: Int,
    val title: String,
    val plot: String,
    val duration: String,
    val rating: String,
    val releaseDate: String,
    val containerExtension: String,
    val coverUrl: String
)

data class MediaDetailInfo(
    val item: LibraryMediaItem,
    val description: String,
    val genre: String,
    val releaseDate: String,
    val cast: String,
    val director: String,
    val duration: String,
    val rating: String,
    val backdropUrl: String,
    val seasons: List<SeriesSeason> = emptyList(),
    val episodesBySeason: Map<Int, List<SeriesEpisode>> = emptyMap()
)

data class PlaybackRequest(
    val title: String,
    val subtitle: String = "",
    val isLiveChannel: Boolean,
    val candidateUrls: List<String>,
    val servers: List<ChannelServer> = emptyList(),
    val channelId: String = ""
)

// --- Real Quality Probing & Local Download Models ---

data class DownloadQualityOption(
    val id: String,
    val qualityTitle: String,      // e.g. "الجودة الأصلية الفائقة (Full HD 1080p)"
    val resolutionBadge: String,   // e.g. "1080p FHD"
    val extension: String,         // Verified working extension: "mp4", "mkv", "ts", "m3u8"
    val streamUrl: String,         // Verified working stream URL on the server
    val totalBytes: Long,          // Real Content-Length from server probe (or estimated if chunked)
    val serverLabel: String,       // e.g. "سيرفر Xtreme الرئيسي • متصل"
    val isHlsStream: Boolean = false
) {
    val formattedSize: String
        get() = if (totalBytes > 0L) formatFileSize(totalBytes) else "حجم تلقائي مباشر"
}

enum class DownloadStatus {
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

data class DownloadTaskItem(
    val id: String,
    val mediaId: String,
    val title: String,
    val subtitle: String,
    val posterUrl: String,
    val isSeries: Boolean,
    val qualityLabel: String,
    val extension: String,
    val sourceUrl: String,
    val localFilePath: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val etaSeconds: Long,
    val status: DownloadStatus,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else if (status == DownloadStatus.COMPLETED) {
            1f
        } else {
            0f
        }

    val progressPercent: Int
        get() = (progressFraction * 100f).toInt().coerceIn(0, 100)

    val formattedSpeed: String
        get() = when {
            status != DownloadStatus.DOWNLOADING -> "—"
            speedBytesPerSec <= 0L -> "جاري الاتصال..."
            speedBytesPerSec >= 1024L * 1024L -> String.format(
                Locale.US,
                "%.2f MB/s",
                speedBytesPerSec.toDouble() / (1024.0 * 1024.0)
            )
            else -> String.format(
                Locale.US,
                "%.0f KB/s",
                speedBytesPerSec.toDouble() / 1024.0
            )
        }

    val formattedSizeProgress: String
        get() {
            val downStr = formatFileSize(downloadedBytes)
            return if (totalBytes > 0L) {
                "$downStr / ${formatFileSize(totalBytes)}"
            } else {
                downStr
            }
        }

    val formattedTotalSize: String
        get() = formatFileSize(if (downloadedBytes > 0L && status == DownloadStatus.COMPLETED) downloadedBytes else totalBytes)

    val formattedEta: String
        get() {
            if (status == DownloadStatus.COMPLETED) return "اكتمل التحميل"
            if (status == DownloadStatus.PAUSED) return "متوقف مؤقتاً"
            if (status == DownloadStatus.FAILED) return "توقف بسبب خطأ"
            if (etaSeconds <= 0L || speedBytesPerSec <= 0L) return "جاري الحساب..."
            val hours = etaSeconds / 3600
            val minutes = (etaSeconds % 3600) / 60
            val seconds = etaSeconds % 60
            return if (hours > 0) {
                String.format(Locale.US, "%02d:%02d:%02d متبقي", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d متبقي", minutes, seconds)
            }
        }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        String.format(Locale.US, "%.0f KB", bytes.toDouble() / 1024.0)
    }
}

// --- 365Scores Matches & Details Models ---

data class MatchCompetitor(
    val id: Int,
    val name: String,
    val symbolicName: String,
    val score: Int,
    val hasScore: Boolean,
    val imageVersion: Int,
    val color: String,
    val awayColor: String,
    val redCards: Int = 0
) {
    val logoUrl: String
        get() = "https://imagecache.365scores.com/image/upload/f_png,w_120,h_120,c_limit,q_auto:eco,dpr_2,d_Competitors:default1.png/v${imageVersion.coerceAtLeast(1)}/Competitors/$id"
}

data class MatchItem(
    val id: Int,
    val competitionId: Int,
    val competitionName: String,
    val competitionImageVersion: Int,
    val startTimeIso: String,
    val displayTime: String,
    val statusGroup: Int,
    val statusText: String,
    val gameTimeDisplay: String,
    val roundName: String,
    val homeTeam: MatchCompetitor,
    val awayTeam: MatchCompetitor
) {
    val isLive: Boolean get() = statusGroup == 3
    val isFinished: Boolean get() = statusGroup == 4
    val competitionLogoUrl: String
        get() = "https://imagecache.365scores.com/image/upload/f_png,w_80,h_80,c_limit,q_auto:eco,dpr_2,d_Competitions:default.png/v${competitionImageVersion.coerceAtLeast(1)}/Competitions/$competitionId"
}

data class LeagueMatchesGroup(
    val competitionId: Int,
    val competitionName: String,
    val countryId: Int,
    val imageVersion: Int,
    val color: String,
    val priorityRank: Int,
    val matches: List<MatchItem>
) {
    val logoUrl: String
        get() = "https://imagecache.365scores.com/image/upload/f_png,w_80,h_80,c_limit,q_auto:eco,dpr_2,d_Competitions:default.png/v${imageVersion.coerceAtLeast(1)}/Competitions/$competitionId"
}

data class MatchEventItem(
    val id: String,
    val order: Int,
    val gameTime: Int,
    val minuteDisplay: String,
    val competitorId: Int,
    val isHome: Boolean,
    val typeId: Int,
    val typeName: String,
    val subTypeName: String,
    val playerName: String,
    val secondaryPlayerName: String,
    val playerPhotoUrl: String
)

data class LineupPlayer(
    val id: Int,
    val athleteId: Int,
    val name: String,
    val shortName: String,
    val jerseyNumber: Int,
    val positionName: String,
    val fieldLine: Int,
    val fieldSide: Int,
    val yardLine: Int,
    val ranking: Double,
    val isStarting: Boolean,
    val imageVersion: Int
) {
    val photoUrl: String
        get() = if (athleteId > 0) {
            "https://imagecache.365scores.com/image/upload/f_png,w_120,h_120,c_limit,q_auto:eco,dpr_2,d_Athletes:default.png/v${imageVersion.coerceAtLeast(1)}/Athletes/$athleteId"
        } else {
            "https://imagecache.365scores.com/image/upload/f_png,w_120,h_120,c_limit,q_auto:eco,dpr_2,d_Athletes:default.png/Athletes/$id"
        }
}

data class TeamLineup(
    val competitorId: Int,
    val teamName: String,
    val teamColor: String,
    val formation: String,
    val statusText: String,
    val startingPlayers: List<LineupPlayer>,
    val substitutes: List<LineupPlayer>
)

data class MatchStatComparison(
    val statId: Int,
    val name: String,
    val homeValue: String,
    val awayValue: String,
    val homeRatio: Float,
    val awayRatio: Float,
    val isPossession: Boolean,
    val order: Int
)

data class StandingRowItem(
    val position: Int,
    val competitorId: Int,
    val teamName: String,
    val imageVersion: Int,
    val teamColor: String,
    val played: Int,
    val won: Int,
    val drawn: Int,
    val lost: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val goalDiff: Int,
    val points: Int,
    val isHomeMatchTeam: Boolean,
    val isAwayMatchTeam: Boolean
) {
    val logoUrl: String
        get() = "https://imagecache.365scores.com/image/upload/f_png,w_80,h_80,c_limit,q_auto:eco,dpr_2,d_Competitors:default1.png/v${imageVersion.coerceAtLeast(1)}/Competitors/$competitorId"
}

data class NewsArticleItem(
    val id: Long,
    val title: String,
    val publishDate: String,
    val formattedDate: String,
    val imageUrl: String,
    val url: String
)

data class MatchDetailData(
    val match: MatchItem,
    val events: List<MatchEventItem>,
    val homeLineup: TeamLineup?,
    val awayLineup: TeamLineup?,
    val stats: List<MatchStatComparison>,
    val standings: List<StandingRowItem>,
    val standingsLeagueName: String,
    val news: List<NewsArticleItem>,
    val venueName: String,
    val refereeName: String
)

// --- Secondary Firebase Database (foul-aeed9) Models: Channel Views/Likes, User Auth & Contact Us ---

data class ChannelEngagementStats(
    val channelId: String,
    val views: Int = 0,
    val likes: Int = 0,
    val isLikedByMe: Boolean = false
)

data class AppAvatarSpec(
    val id: String,
    val nameAr: String,
    val isBoy: Boolean,
    val primaryHex: Long,
    val secondaryHex: Long,
    val accentHex: Long,
    val styleVariant: Int
)

data class SocialUserProfile(
    val uid: String,
    val email: String,
    val username: String,
    val avatarId: String
)

data class ContactMessageItem(
    val id: String,
    val senderUid: String,
    val senderName: String,
    val senderEmail: String,
    val senderAvatarId: String,
    val text: String,
    val timestamp: Long,
    val isFromBotOrAdmin: Boolean = false,
    val targetUid: String = "",
    val replyToMessageId: String = "",
    val replyToSenderName: String = "",
    val replyToText: String = ""
)

// --- 365Scores Player Career, Full Statistics, Transfers & Team Search Models ---

data class PlayerTransferItem(
    val id: String,
    val playerName: String = "",
    val playerPhotoUrl: String = "",
    val fromTeamId: Int = 0,
    val fromTeamName: String,
    val fromTeamLogoUrl: String,
    val toTeamId: Int = 0,
    val toTeamName: String,
    val toTeamLogoUrl: String,
    val dateText: String,
    val transferTypeOrFee: String
)

data class PlayerLeagueCareerStat(
    val id: String,
    val competitionId: Int,
    val competitionName: String,
    val competitionLogoUrl: String,
    val seasonName: String,
    val teamName: String,
    val teamLogoUrl: String,
    val appearances: Int,
    val goals: Int,
    val assists: Int,
    val yellowCards: Int,
    val redCards: Int,
    val minutesPlayed: Int = 0,
    val rating: String = "-"
)

data class PlayerGeneralStatItem(
    val label: String,
    val value: String,
    val progressRatio: Float = 0.7f
)

data class PlayerFullProfile(
    val id: Int,
    val athleteId: Int,
    val name: String,
    val shortName: String,
    val photoUrl: String,
    val positionName: String,
    val jerseyNumber: Int,
    val nationality: String,
    val nationalityFlagUrl: String = "",
    val age: String = "",
    val birthDate: String = "",
    val height: String = "",
    val weight: String = "",
    val preferredFoot: String = "",
    val marketValue: String = "",
    val currentTeamId: Int = 0,
    val currentTeamName: String,
    val currentTeamLogoUrl: String,
    val transfers: List<PlayerTransferItem> = emptyList(),
    val leagueCareerStats: List<PlayerLeagueCareerStat> = emptyList(),
    val fullStatistics: List<PlayerGeneralStatItem> = emptyList(),
    val recentMatches: List<MatchItem> = emptyList()
)

data class TeamFullProfile(
    val id: Int,
    val name: String,
    val logoUrl: String,
    val countryName: String,
    val venueName: String = "",
    val coachName: String = "",
    val primaryCompetitionName: String = "",
    val primaryCompetitionLogoUrl: String = "",
    val playedMatches: List<MatchItem> = emptyList(),
    val upcomingMatches: List<MatchItem> = emptyList(),
    val transfers: List<PlayerTransferItem> = emptyList(),
    val squadPlayers: List<PlayerFullProfile> = emptyList(),
    val standings: List<StandingRowItem> = emptyList()
)

data class MatchesSearchResult(
    val query: String,
    val players: List<PlayerFullProfile> = emptyList(),
    val teams: List<MatchCompetitor> = emptyList()
)



