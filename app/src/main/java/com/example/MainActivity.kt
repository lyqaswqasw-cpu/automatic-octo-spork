package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.BottomNavTab
import com.example.ui.components.OrderedColorfulDivider
import com.example.ui.screens.AnimatedBotIcon
import com.example.ui.screens.ContactUsScreen
import com.example.ui.screens.DeveloperControlPanelScreen
import com.example.ui.screens.ForcedUpdateLockScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.LibraryVideoPlayerScreen
import com.example.ui.screens.MatchesScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.FoulBlackBg
import com.example.ui.theme.FoulBottomBarBlack
import com.example.ui.theme.FoulPurpleBright
import com.example.ui.theme.FoulPurpleDeep
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.FoulTvViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                FoulTvApp()
            }
        }
    }
}

private data class NavItemSpec(
    val tab: BottomNavTab,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val NAV_ITEMS_START = listOf(
    NavItemSpec(
        tab = BottomNavTab.HOME,
        label = "الرئيسية",
        selectedIcon = Icons.Filled.LiveTv,
        unselectedIcon = Icons.Outlined.LiveTv,
        testTag = "nav_tab_home"
    ),
    NavItemSpec(
        tab = BottomNavTab.LIBRARY,
        label = "المكتبة",
        selectedIcon = Icons.Filled.VideoLibrary,
        unselectedIcon = Icons.Outlined.VideoLibrary,
        testTag = "nav_tab_library"
    )
)

private val NAV_ITEMS_END = listOf(
    NavItemSpec(
        tab = BottomNavTab.MATCHES,
        label = "جدول المباريات",
        selectedIcon = Icons.Filled.SportsSoccer,
        unselectedIcon = Icons.Outlined.SportsSoccer,
        testTag = "nav_tab_matches"
    ),
    NavItemSpec(
        tab = BottomNavTab.SETTINGS,
        label = "الإعدادات",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_tab_settings"
    )
)

@Composable
fun FoulTvApp(
    viewModel: FoulTvViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 1. Fullscreen Landscape Player overrides Scaffold when active (Supports both Online and Offline Local files)
    val activePlayback = uiState.activePlayback
    if (activePlayback != null) {
        val activeChannelStats = if (activePlayback.isLiveChannel && activePlayback.channelId.isNotBlank()) {
            uiState.channelStatsMap[activePlayback.channelId]
        } else null

        LibraryVideoPlayerScreen(
            playbackRequest = activePlayback,
            channelStats = activeChannelStats,
            showChannelViewsAndLikes = uiState.showChannelViewsAndLikes,
            onToggleChannelLike = {
                if (activePlayback.channelId.isNotBlank()) {
                    viewModel.toggleChannelLike(activePlayback.channelId)
                }
            },
            onBack = { viewModel.closePlayer() }
        )
        return
    }

    // 2. Full-Screen Independent "Contact Us" ("تواصل معنا") + User Auth & Dedicated Avatar Picker Page
    if (uiState.isContactUsScreenOpen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FoulBlackBg)
                .windowInsetsPadding(WindowInsets.statusBars)
                .navigationBarsPadding()
        ) {
            ContactUsScreen(
                currentUser = uiState.currentSocialUser,
                messages = uiState.contactMessages,
                isAuthLoading = uiState.isSocialAuthLoading,
                authError = uiState.socialAuthError,
                onClearAuthError = viewModel::clearSocialAuthError,
                onLogin = viewModel::loginSocialUser,
                onRegister = viewModel::registerSocialUser,
                onLogout = viewModel::logoutSocialUser,
                onSendMessage = viewModel::sendContactUsMessage,
                onRefreshMessages = viewModel::refreshContactUsMessages,
                onBack = viewModel::closeContactUsScreen
            )
        }
        return
    }

    // 3. Completely Isolated Developer Control Panel Page ("وبعد الدخول تفتح صفحة منعزلة كلياً")
    if (uiState.isDeveloperPanelOpen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FoulBlackBg)
                .windowInsetsPadding(WindowInsets.statusBars)
                .navigationBarsPadding()
        ) {
            DeveloperControlPanelScreen(
                developerEmail = uiState.developerEmail,
                isActionLoading = uiState.isDevActionLoading,
                statusMessage = uiState.devStatusMessage,
                branches = uiState.categories,
                channels = uiState.channels,
                xtreamAccounts = uiState.xtreamAccounts,
                versionConfig = uiState.versionConfig,
                userMessages = uiState.developerContactMessages,
                showChannelViewsAndLikes = uiState.showChannelViewsAndLikes,
                onDismissStatusMessage = viewModel::clearDevStatusMessage,
                onSaveBranch = viewModel::saveDeveloperBranch,
                onMoveBranchOrder = viewModel::moveBranchInOrder,
                onSaveBranchesOrder = viewModel::saveCurrentBranchesOrder,
                onDeleteBranch = viewModel::deleteDeveloperBranch,
                onSaveChannel = viewModel::saveDeveloperChannel,
                onDeleteChannel = viewModel::deleteDeveloperChannel,
                onSaveXtreamAccount = viewModel::saveDeveloperXtreamAccount,
                onDeleteXtreamAccount = viewModel::deleteDeveloperXtreamAccount,
                onSaveVersionConfig = viewModel::saveDeveloperVersionConfig,
                onToggleShowChannelViewsAndLikes = viewModel::toggleShowChannelViewsAndLikesOnline,
                onSendDeveloperReply = viewModel::sendDeveloperReplyToUser,
                onRefreshUserMessages = viewModel::refreshDeveloperUserMessages,
                onRefreshData = {
                    viewModel.loadHomeData(forceRefresh = true)
                    viewModel.refreshDeveloperUserMessages()
                },
                onLogoutDeveloper = viewModel::logoutDeveloper,
                onClosePanel = viewModel::closeDeveloperPanel
            )
        }
        return
    }


    // 5. Mandatory Update Lock Screen when published version != 1.0 ("ولا يفتح تطبيق إطلاقاً فقط للنسخة إلي نشرت")
    if (uiState.versionConfig.isForcedUpdateLocked) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FoulBlackBg)
                .windowInsetsPadding(WindowInsets.statusBars)
                .navigationBarsPadding()
        ) {
            ForcedUpdateLockScreen(
                versionConfig = uiState.versionConfig,
                isDevLoginSheetOpen = uiState.isDevLoginSheetOpen,
                isLoggingInDeveloper = uiState.isLoggingInDeveloper,
                devLoginError = uiState.devLoginError,
                onOpenDeveloperSheet = viewModel::openDeveloperLoginSheet,
                onDismissDeveloperSheet = viewModel::closeDeveloperLoginSheet,
                onSubmitDeveloperLogin = viewModel::loginDeveloper
            )
        }
        return
    }

    // Back handler when on secondary tabs returns to Home tab
    BackHandler(
        enabled = uiState.currentTab != BottomNavTab.HOME &&
            uiState.selectedMediaItem == null &&
            !uiState.isLibrarySearchOpen &&
            uiState.selectedMatch == null &&
            !uiState.isMatchesSearchOpen &&
            uiState.selectedPlayerProfile == null &&
            uiState.selectedTeamProfile == null &&
            !uiState.isDevLoginSheetOpen
    ) {
        viewModel.selectTab(BottomNavTab.HOME)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        containerColor = FoulBlackBg,
        bottomBar = {
            FoulCompactBottomBar(
                currentTab = uiState.currentTab,
                onSelectTab = viewModel::selectTab,
                onOpenContactUs = viewModel::openContactUsScreen
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (uiState.currentTab) {
                BottomNavTab.HOME -> {
                    HomeScreen(
                        isLoading = uiState.isLoadingHome,
                        error = uiState.homeError,
                        currentBranch = uiState.currentHomeBranch,
                        visibleBranches = uiState.visibleHomeBranches,
                        visibleChannels = uiState.visibleBranchChannels,
                        allChannels = uiState.channels,
                        allBranches = uiState.categories,
                        banners = uiState.banners,
                        channelStatsMap = uiState.channelStatsMap,
                        showChannelViewsAndLikes = uiState.showChannelViewsAndLikes,
                        onOpenBranch = viewModel::openHomeBranch,
                        onBackFromBranch = viewModel::navigateBackHomeBranch,
                        onChannelClick = viewModel::playLiveChannel,
                        onToggleChannelLike = { ch -> viewModel.toggleChannelLike(ch.id) },
                        onOpenContactUs = viewModel::openContactUsScreen,
                        onBannerClick = viewModel::onBannerClick,
                        onRefresh = { viewModel.loadHomeData(forceRefresh = true) }
                    )
                }

                BottomNavTab.LIBRARY -> {
                    LibraryScreen(
                        isLoading = uiState.isLoadingLibrary,
                        error = uiState.libraryError,
                        mainFilter = uiState.libraryMainFilter,
                        items = uiState.libraryDisplayedItems,
                        hasMore = uiState.libraryHasMore,
                        isSearchOpen = uiState.isLibrarySearchOpen,
                        searchQuery = uiState.searchQuery,
                        searchFilter = uiState.searchFilter,
                        searchResults = uiState.searchResults,
                        isSearching = uiState.isSearching,
                        selectedMediaItem = uiState.selectedMediaItem,
                        isLoadingDetail = uiState.isLoadingMediaDetail,
                        detailError = uiState.mediaDetailError,
                        mediaDetail = uiState.mediaDetail,
                        selectedSeasonNumber = uiState.selectedSeasonNumber,
                        onSelectMainFilter = viewModel::selectLibraryMainFilter,
                        onLoadMore = viewModel::loadMoreLibraryItems,
                        onOpenSearch = viewModel::openLibrarySearch,
                        onCloseSearch = viewModel::closeLibrarySearch,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onSearchFilterChange = viewModel::updateSearchFilter,
                        onItemClick = viewModel::openMediaDetail,
                        onCloseDetail = viewModel::closeMediaDetail,
                        onSelectSeason = viewModel::selectSeason,
                        onPlayMovie = viewModel::playLibraryMovie,
                        onPlayEpisode = viewModel::playLibraryEpisode,
                        onRetry = viewModel::loadLibraryInitial
                    )
                }

                BottomNavTab.MATCHES -> {
                    MatchesScreen(
                        dateOptions = uiState.dateOptions,
                        selectedDate = uiState.selectedDateString,
                        isLoading = uiState.isLoadingMatches,
                        error = uiState.matchesError,
                        leagueGroups = uiState.leagueGroups,
                        selectedMatch = uiState.selectedMatch,
                        isLoadingMatchDetail = uiState.isLoadingMatchDetail,
                        matchDetailError = uiState.matchDetailError,
                        matchDetail = uiState.matchDetail,
                        isMatchesSearchOpen = uiState.isMatchesSearchOpen,
                        matchesSearchQuery = uiState.matchesSearchQuery,
                        isSearchingMatches = uiState.isSearchingMatches,
                        matchesSearchResult = uiState.matchesSearchResult,
                        selectedPlayerProfile = uiState.selectedPlayerProfile,
                        isLoadingPlayerProfile = uiState.isLoadingPlayerProfile,
                        playerProfileError = uiState.playerProfileError,
                        selectedTeamProfile = uiState.selectedTeamProfile,
                        isLoadingTeamProfile = uiState.isLoadingTeamProfile,
                        teamProfileError = uiState.teamProfileError,
                        onSelectDate = viewModel::loadMatchesForDate,
                        onMatchClick = viewModel::openMatchDetail,
                        onCloseMatchDetail = viewModel::closeMatchDetail,
                        onOpenMatchesSearch = viewModel::openMatchesSearch,
                        onCloseMatchesSearch = viewModel::closeMatchesSearch,
                        onMatchesSearchQueryChange = viewModel::updateMatchesSearchQuery,
                        onPlayerClickFromLineup = viewModel::openPlayerProfileFromLineup,
                        onPlayerSelectFromSearch = viewModel::openPlayerProfileFromSearch,
                        onClosePlayerProfile = viewModel::closePlayerProfile,
                        onTeamSelectFromSearch = viewModel::openTeamProfile,
                        onCloseTeamProfile = viewModel::closeTeamProfile,
                        onRefresh = { viewModel.loadMatchesForDate(uiState.selectedDateString) }
                    )
                }

                BottomNavTab.SETTINGS -> {
                    SettingsScreen(
                        telegramUrl = uiState.telegramUrl,
                        currentUser = uiState.currentSocialUser,
                        isDevLoginSheetOpen = uiState.isDevLoginSheetOpen,
                        isLoggingInDeveloper = uiState.isLoggingInDeveloper,
                        devLoginError = uiState.devLoginError,
                        onOpenContactUsScreen = viewModel::openContactUsScreen,
                        onOpenDeveloperSheet = viewModel::openDeveloperLoginSheet,
                        onDismissDeveloperSheet = viewModel::closeDeveloperLoginSheet,
                        onSubmitDeveloperLogin = viewModel::loginDeveloper
                    )
                }
            }
        }
    }
}

/**
 * Sleek, compact, pitch-black bottom bar with ordered colorful top divider line,
 * featuring the elevated Animated Bot "تواصل معنا" icon button positioned right between
 * "المكتبة" (Library) and "جدول المباريات" (Matches), raised slightly above the bottom bar.
 */
@Composable
private fun FoulCompactBottomBar(
    currentTab: BottomNavTab,
    onSelectTab: (BottomNavTab) -> Unit,
    onOpenContactUs: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(FoulBottomBarBlack)
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Ordered colorful top divider line
            OrderedColorfulDivider(thickness = 1.5.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // First two tabs: الرئيسية + المكتبة
                NAV_ITEMS_START.forEach { item ->
                    BottomNavTabCell(
                        item = item,
                        selected = currentTab == item.tab,
                        onClick = { onSelectTab(item.tab) }
                    )
                }

                // Center Elevated "تواصل معنا" Animated Bot Button between المكتبة and جدول المباريات
                // ("تكون أيقونة التواصل مابين قسم المكتبة وقسم جدول المباريات مرفوعة للأعلى قليلاً فوق شريط سفلي وبشكل مرتب جدا وانيق")
                Box(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val contactInteraction = remember { MutableInteractionSource() }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .offset(y = (-13).dp)
                            .clickable(
                                interactionSource = contactInteraction,
                                indication = null,
                                onClick = onOpenContactUs
                            )
                            .testTag("bottom_bar_contact_us_button")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .shadow(
                                    elevation = 10.dp,
                                    shape = CircleShape,
                                    ambientColor = Color(0xFF00E5FF),
                                    spotColor = FoulPurpleBright
                                )
                                .clip(CircleShape)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            FoulPurpleBright,
                                            FoulPurpleDeep,
                                            FoulBottomBarBlack
                                        )
                                    )
                                )
                                .border(
                                    width = 1.6.dp,
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFF00E5FF),
                                            Color.White,
                                            FoulPurpleBright
                                        )
                                    ),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedBotIcon(size = 34.dp)
                        }

                        Spacer(Modifier.height(1.dp))

                        Text(
                            text = "تواصل معنا",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                    }
                }

                // Last two tabs: جدول المباريات + الإعدادات
                NAV_ITEMS_END.forEach { item ->
                    BottomNavTabCell(
                        item = item,
                        selected = currentTab == item.tab,
                        onClick = { onSelectTab(item.tab) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.BottomNavTabCell(
    item: NavItemSpec,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(horizontal = 3.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) FoulPurpleDeep else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .testTag(item.testTag),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                contentDescription = item.label,
                tint = if (selected) Color.White else Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(19.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
                color = if (selected) Color.White else Color.White.copy(alpha = 0.55f),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
        }

        // Subtle top active indicator pill
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .width(20.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White)
            )
        }
    }
}
