package org.schabi.newpipe.youtube

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeAccountSyncExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.util.InfoCache
import org.schabi.newpipe.util.ServiceHelper
import org.schabi.newpipe.util.Localization as AppLocalization

/**
 * Coordinates syncing subscribed channels, watch history, and playback reporting between
 * the user's logged-in YouTube account and PipePipe's local Room database.
 */
object YouTubeAccountSyncManager {
    private const val TAG = "YouTubeAccountSync"
    private const val STARTUP_SYNC_DELAY_MS = 20_000L
    private const val AUTO_SYNC_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val DEFAULT_HISTORY_PAGES = 5

    data class SubscriptionSyncResult(
        val totalFetched: Int,
        val addedCount: Int,
        val updatedCount: Int,
    )

    data class HistorySyncResult(
        val totalFetched: Int,
        val addedCount: Int,
        val existingCount: Int,
    )

    data class FullSyncResult(
        val subscriptions: SubscriptionSyncResult,
        val history: HistorySyncResult,
    )

    @JvmStatic
    fun isLoggedIn(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val cookies = prefs.getString(context.getString(R.string.youtube_cookies_key), null)
        return !cookies.isNullOrBlank()
    }

    @JvmStatic
    fun syncSubscriptions(context: Context): Single<SubscriptionSyncResult> {
        val appContext = context.applicationContext
        return Single.fromCallable {
            ServiceHelper.initService(appContext, ServiceList.YouTube.serviceId)
            val country = AppLocalization.getPreferredContentCountry(appContext)
            val channels = YoutubeAccountSyncExtractor.fetchSubscribedChannels(
                Localization("en"),
                country,
            )

            val db = NewPipeDatabase.getInstance(appContext)
            val dao = db.subscriptionDAO()
            val ytServiceId = ServiceList.YouTube.serviceId

            var added = 0
            var updated = 0

            db.runInTransaction {
                for (channel in channels) {
                    val existing = dao.getSubscription(ytServiceId, channel.url).blockingGet()
                    if (existing != null) {
                        var changed = false
                        if (channel.name.isNotBlank() && channel.name != existing.name) {
                            existing.name = channel.name
                            changed = true
                        }
                        if (!channel.avatarUrl.isNullOrBlank() && channel.avatarUrl != existing.avatarUrl) {
                            existing.avatarUrl = channel.avatarUrl
                            changed = true
                        }
                        if (channel.subscriberCount >= 0 && channel.subscriberCount != existing.subscriberCount) {
                            existing.subscriberCount = channel.subscriberCount
                            changed = true
                        }
                        if (!channel.description.isNullOrBlank() && existing.description.isNullOrBlank()) {
                            existing.description = channel.description
                            changed = true
                        }
                        if (changed) {
                            dao.update(existing)
                            updated++
                        }
                    } else {
                        val entity = SubscriptionEntity().apply {
                            serviceId = ytServiceId
                            url = channel.url
                            name = channel.name
                            avatarUrl = channel.avatarUrl
                            subscriberCount = channel.subscriberCount
                            description = channel.description
                        }
                        dao.insert(entity)
                        added++
                    }
                }
            }

            SubscriptionSyncResult(
                totalFetched = channels.size,
                addedCount = added,
                updatedCount = updated,
            )
        }.subscribeOn(Schedulers.io())
    }

    @JvmStatic
    @JvmOverloads
    fun syncWatchHistory(
        context: Context,
        maxPages: Int = DEFAULT_HISTORY_PAGES,
    ): Single<HistorySyncResult> {
        val appContext = context.applicationContext
        return Single.fromCallable {
            ServiceHelper.initService(appContext, ServiceList.YouTube.serviceId)
            val country = AppLocalization.getPreferredContentCountry(appContext)
            val items = YoutubeAccountSyncExtractor.fetchWatchHistory(
                Localization("en"),
                country,
                maxPages,
            )

            val historyManager = HistoryRecordManager(appContext)
            val added = historyManager.syncFromYouTubeHistory(items)
            HistorySyncResult(
                totalFetched = items.size,
                addedCount = added,
                existingCount = (items.size - added).coerceAtLeast(0),
            )
        }.subscribeOn(Schedulers.io())
    }

    @JvmStatic
    fun syncAll(context: Context): Single<FullSyncResult> {
        val appContext = context.applicationContext
        return Single.zip(
            syncSubscriptions(appContext),
            syncWatchHistory(appContext),
        ) { subs, hist ->
            InfoCache.getInstance().clearCache()
            PreferenceManager.getDefaultSharedPreferences(appContext)
                .edit()
                .putLong(
                    appContext.getString(R.string.youtube_last_sync_time_key),
                    System.currentTimeMillis(),
                )
                .apply()
            FullSyncResult(subs, hist)
        }.subscribeOn(Schedulers.io())
    }

    @JvmStatic
    fun scheduleStartupSync(context: Context) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isLoggedIn(appContext)) {
                return@postDelayed
            }
            val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
            val autoSyncEnabled = prefs.getBoolean(
                appContext.getString(R.string.youtube_auto_sync_key),
                true,
            )
            if (!autoSyncEnabled) {
                return@postDelayed
            }
            val lastSync = prefs.getLong(
                appContext.getString(R.string.youtube_last_sync_time_key),
                0L,
            )
            if (System.currentTimeMillis() - lastSync < AUTO_SYNC_INTERVAL_MS) {
                return@postDelayed
            }

            syncAll(appContext)
                .subscribe(
                    { result ->
                        Log.i(
                            TAG,
                            "Auto-sync complete: +${result.subscriptions.addedCount} subs, " +
                                "+${result.history.addedCount} history items",
                        )
                    },
                    { err ->
                        Log.w(TAG, "Auto-sync skipped due to error", err)
                    },
                )
        }, STARTUP_SYNC_DELAY_MS)
    }

    @JvmStatic
    fun reportStreamPlayback(context: Context, info: StreamInfo) {
        val appContext = context.applicationContext
        if (info.serviceId != ServiceList.YouTube.serviceId) {
            return
        }
        if (!isLoggedIn(appContext)) {
            return
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        val reportEnabled = prefs.getBoolean(
            appContext.getString(R.string.youtube_report_history_key),
            true,
        )
        if (!reportEnabled) {
            return
        }
        val playbackUrl = info.playbackTrackingUrl
        val watchtimeUrl = info.watchtimeTrackingUrl
        if (playbackUrl.isNullOrBlank() && watchtimeUrl.isNullOrBlank()) {
            return
        }

        Schedulers.io().scheduleDirect {
            try {
                ServiceHelper.initService(appContext, ServiceList.YouTube.serviceId)
                YoutubeAccountSyncExtractor.reportPlaybackToYouTube(
                    playbackUrl,
                    watchtimeUrl,
                    info.duration,
                    Localization("en"),
                )
            } catch (e: Exception) {
                Log.d(TAG, "Failed to report playback to YouTube watch history", e)
            }
        }
    }
}
