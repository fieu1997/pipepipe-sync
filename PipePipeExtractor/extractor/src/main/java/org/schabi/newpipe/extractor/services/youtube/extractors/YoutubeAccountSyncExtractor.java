package org.schabi.newpipe.extractor.services.youtube.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.localization.TimeAgoParser;
import org.schabi.newpipe.extractor.localization.TimeAgoPatternsManager;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static java.util.Collections.singletonList;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.MWEB_USER_AGENT;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.addLoggedInHeaders;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.fixThumbnailUrl;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getChannelIdFromNavigationEndpoint;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getLoggedInJsonPostResponse;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareDesktopJsonBuilder;
import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;

/**
 * Fetches authenticated YouTube account data (subscribed channels, watch history) and optionally
 * reports playback events back to YouTube using the active logged-in session cookies.
 */
public final class YoutubeAccountSyncExtractor {
    public static final String BROWSE_ID_SUBSCRIPTIONS = "FEchannels";
    public static final String BROWSE_ID_HISTORY = "FEhistory";
    public static final String BROWSE_ID_HOME = "FEwhat_to_watch";

    private static final int MAX_SUBSCRIPTION_PAGES = 50;
    private static final int DEFAULT_HISTORY_PAGES = 5;

    private YoutubeAccountSyncExtractor() {
    }

    public static final class YoutubeSubscribedChannel implements Serializable {
        private final String channelId;
        private final String url;
        private final String name;
        @Nullable
        private final String avatarUrl;
        private final long subscriberCount;
        @Nullable
        private final String description;

        public YoutubeSubscribedChannel(@Nonnull final String channelId,
                                        @Nonnull final String url,
                                        @Nonnull final String name,
                                        @Nullable final String avatarUrl,
                                        final long subscriberCount,
                                        @Nullable final String description) {
            this.channelId = channelId;
            this.url = url;
            this.name = name;
            this.avatarUrl = avatarUrl;
            this.subscriberCount = subscriberCount;
            this.description = description;
        }

        @Nonnull
        public String getChannelId() {
            return channelId;
        }

        @Nonnull
        public String getUrl() {
            return url;
        }

        @Nonnull
        public String getName() {
            return name;
        }

        @Nullable
        public String getAvatarUrl() {
            return avatarUrl;
        }

        public long getSubscriberCount() {
            return subscriberCount;
        }

        @Nullable
        public String getDescription() {
            return description;
        }
    }

    public static final class YoutubeHistoryItem implements Serializable {
        private final StreamInfoItem streamInfoItem;
        private final int percentWatched;

        public YoutubeHistoryItem(@Nonnull final StreamInfoItem streamInfoItem,
                                  final int percentWatched) {
            this.streamInfoItem = streamInfoItem;
            this.percentWatched = Math.max(0, Math.min(100, percentWatched));
        }

        @Nonnull
        public StreamInfoItem getStreamInfoItem() {
            return streamInfoItem;
        }

        public int getPercentWatched() {
            return percentWatched;
        }
    }

    public static final class HistoryPageResult {
        private final List<YoutubeHistoryItem> items;
        @Nullable
        private final String continuationToken;

        public HistoryPageResult(@Nonnull final List<YoutubeHistoryItem> items,
                                 @Nullable final String continuationToken) {
            this.items = items;
            this.continuationToken = continuationToken;
        }

        @Nonnull
        public List<YoutubeHistoryItem> getItems() {
            return items;
        }

        @Nullable
        public String getContinuationToken() {
            return continuationToken;
        }
    }

    public static final class SubscriptionsPageResult {
        private final List<YoutubeSubscribedChannel> channels;
        @Nullable
        private final String continuationToken;

        public SubscriptionsPageResult(@Nonnull final List<YoutubeSubscribedChannel> channels,
                                       @Nullable final String continuationToken) {
            this.channels = channels;
            this.continuationToken = continuationToken;
        }

        @Nonnull
        public List<YoutubeSubscribedChannel> getChannels() {
            return channels;
        }

        @Nullable
        public String getContinuationToken() {
            return continuationToken;
        }
    }

    @Nonnull
    public static List<YoutubeSubscribedChannel> fetchSubscribedChannels(
            @Nonnull final Localization localization,
            @Nonnull final ContentCountry contentCountry)
            throws IOException, ExtractionException {
        ensureLoggedIn();

        final Map<String, YoutubeSubscribedChannel> dedup = new LinkedHashMap<>();
        final Set<String> seenTokens = new HashSet<>();

        byte[] body = JsonWriter.string(
                prepareDesktopJsonBuilder(localization, contentCountry)
                        .value("browseId", BROWSE_ID_SUBSCRIPTIONS)
                        .done())
                .getBytes(StandardCharsets.UTF_8);

        int pageCount = 0;
        while (pageCount < MAX_SUBSCRIPTION_PAGES) {
            final JsonObject response = getLoggedInJsonPostResponse("browse", body, localization);
            final SubscriptionsPageResult pageResult = parseSubscribedChannelsFromJson(response);
            for (final YoutubeSubscribedChannel channel : pageResult.getChannels()) {
                dedup.putIfAbsent(channel.getChannelId(), channel);
            }

            final String token = pageResult.getContinuationToken();
            if (isNullOrEmpty(token) || !seenTokens.add(token)) {
                break;
            }
            pageCount++;
            body = JsonWriter.string(
                    prepareDesktopJsonBuilder(localization, contentCountry)
                            .value("continuation", token)
                            .done())
                    .getBytes(StandardCharsets.UTF_8);
        }

        // Fallback to InnerTube guide endpoint if FEchannels returned no channels
        if (dedup.isEmpty()) {
            final byte[] guideBody = JsonWriter.string(
                    prepareDesktopJsonBuilder(localization, contentCountry)
                            .done())
                    .getBytes(StandardCharsets.UTF_8);
            final JsonObject guideResponse =
                    getLoggedInJsonPostResponse("guide", guideBody, localization);
            final SubscriptionsPageResult guideResult =
                    parseSubscribedChannelsFromJson(guideResponse);
            for (final YoutubeSubscribedChannel channel : guideResult.getChannels()) {
                dedup.putIfAbsent(channel.getChannelId(), channel);
            }
        }

        return new ArrayList<>(dedup.values());
    }

    @Nonnull
    public static List<YoutubeHistoryItem> fetchWatchHistory(
            @Nonnull final Localization localization,
            @Nonnull final ContentCountry contentCountry,
            final int maxPages)
            throws IOException, ExtractionException {
        ensureLoggedIn();

        final int pageLimit = maxPages > 0 ? maxPages : DEFAULT_HISTORY_PAGES;
        final TimeAgoParser timeAgoParser = TimeAgoPatternsManager.getTimeAgoParserFor(localization);
        final Map<String, YoutubeHistoryItem> dedup = new LinkedHashMap<>();
        final Set<String> seenTokens = new HashSet<>();

        byte[] body = JsonWriter.string(
                prepareDesktopJsonBuilder(localization, contentCountry)
                        .value("browseId", BROWSE_ID_HISTORY)
                        .done())
                .getBytes(StandardCharsets.UTF_8);

        int pageCount = 0;
        while (pageCount < pageLimit) {
            final JsonObject response = getLoggedInJsonPostResponse("browse", body, localization);
            final HistoryPageResult pageResult = parseHistoryPageFromJson(response, timeAgoParser);
            for (final YoutubeHistoryItem item : pageResult.getItems()) {
                final String url = item.getStreamInfoItem().getUrl();
                if (!isNullOrEmpty(url)) {
                    dedup.putIfAbsent(url, item);
                }
            }

            final String token = pageResult.getContinuationToken();
            if (isNullOrEmpty(token) || !seenTokens.add(token)) {
                break;
            }
            pageCount++;
            body = JsonWriter.string(
                    prepareDesktopJsonBuilder(localization, contentCountry)
                            .value("continuation", token)
                            .done())
                    .getBytes(StandardCharsets.UTF_8);
        }

        return new ArrayList<>(dedup.values());
    }

    public static void reportPlaybackToYouTube(@Nullable final String playbackTrackingUrl,
                                               @Nullable final String watchtimeTrackingUrl,
                                               final long durationSeconds,
                                               @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        if (!ServiceList.YouTube.hasTokens()) {
            return;
        }
        if (isNullOrEmpty(playbackTrackingUrl) && isNullOrEmpty(watchtimeTrackingUrl)) {
            return;
        }

        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("User-Agent", singletonList(MWEB_USER_AGENT));
        headers.put("Origin", singletonList("https://www.youtube.com"));
        headers.put("Referer", singletonList("https://www.youtube.com/"));
        addLoggedInHeaders(headers);

        if (!isNullOrEmpty(playbackTrackingUrl)) {
            NewPipe.getDownloader().get(playbackTrackingUrl, headers, localization);
        }

        if (!isNullOrEmpty(watchtimeTrackingUrl)) {
            final StringBuilder url = new StringBuilder(watchtimeTrackingUrl);
            if (!watchtimeTrackingUrl.contains("st=")) {
                url.append(watchtimeTrackingUrl.contains("?") ? "&" : "?")
                        .append("st=0&et=5&cmt=5&state=playing");
                if (durationSeconds > 0) {
                    url.append("&len=").append(durationSeconds);
                }
            }
            NewPipe.getDownloader().get(url.toString(), headers, localization);
        }
    }

    @Nonnull
    public static SubscriptionsPageResult parseSubscribedChannelsFromJson(
            @Nonnull final JsonObject root) {
        final Map<String, YoutubeSubscribedChannel> channels = new LinkedHashMap<>();
        final String[] continuationHolder = new String[1];
        collectSubscribedChannelsRecursive(root, channels, continuationHolder);
        return new SubscriptionsPageResult(
                new ArrayList<>(channels.values()),
                continuationHolder[0]);
    }

    @Nonnull
    public static HistoryPageResult parseHistoryPageFromJson(
            @Nonnull final JsonObject root,
            @Nullable final TimeAgoParser timeAgoParser) {
        final List<YoutubeHistoryItem> items = new ArrayList<>();
        final Set<String> seenUrls = new HashSet<>();
        final String[] continuationHolder = new String[1];
        collectHistoryItemsRecursive(root, timeAgoParser, items, seenUrls, continuationHolder);
        return new HistoryPageResult(items, continuationHolder[0]);
    }

    @Nullable
    public static String extractContinuationToken(@Nonnull final JsonObject root) {
        final String[] holder = new String[1];
        findContinuationTokenRecursive(root, holder);
        return holder[0];
    }

    private static void ensureLoggedIn() throws ContentNotAvailableException {
        if (!ServiceList.YouTube.hasTokens()) {
            throw new ContentNotAvailableException(
                    "YouTube account login is required. Please log in via Settings > Account > YouTube.");
        }
    }

    private static void collectSubscribedChannelsRecursive(
            @Nullable final Object node,
            @Nonnull final Map<String, YoutubeSubscribedChannel> out,
            @Nonnull final String[] continuationHolder) {
        if (node instanceof JsonArray) {
            for (final Object element : (JsonArray) node) {
                collectSubscribedChannelsRecursive(element, out, continuationHolder);
            }
            return;
        }
        if (!(node instanceof JsonObject)) {
            return;
        }

        final JsonObject obj = (JsonObject) node;

        if (obj.has("channelRenderer")) {
            addChannelFromRenderer(obj.getObject("channelRenderer"), out);
            return;
        }
        if (obj.has("gridChannelRenderer")) {
            addChannelFromRenderer(obj.getObject("gridChannelRenderer"), out);
            return;
        }
        if (obj.has("compactChannelRenderer")) {
            addChannelFromRenderer(obj.getObject("compactChannelRenderer"), out);
            return;
        }
        if (obj.has("guideEntryRenderer")) {
            addChannelFromGuideEntry(obj.getObject("guideEntryRenderer"), out);
            return;
        }
        if (obj.has("lockupViewModel")) {
            final JsonObject lockup = obj.getObject("lockupViewModel");
            if ("LOCKUP_CONTENT_TYPE_CHANNEL".equals(lockup.getString("contentType"))) {
                addChannelFromLockup(lockup, out);
                return;
            }
        }
        if (obj.has("continuationItemRenderer")) {
            final String token = extractTokenFromContinuationItem(
                    obj.getObject("continuationItemRenderer"));
            if (!isNullOrEmpty(token) && isNullOrEmpty(continuationHolder[0])) {
                continuationHolder[0] = token;
            }
            return;
        }

        for (final Map.Entry<String, Object> entry : obj.entrySet()) {
            collectSubscribedChannelsRecursive(entry.getValue(), out, continuationHolder);
        }
    }

    private static void addChannelFromRenderer(@Nonnull final JsonObject renderer,
                                               @Nonnull final Map<String, YoutubeSubscribedChannel> out) {
        try {
            String channelId = renderer.getString("channelId");
            if (isNullOrEmpty(channelId)) {
                channelId = getChannelIdFromNavigationEndpoint(
                        renderer.getObject("navigationEndpoint"));
            }
            if (isNullOrEmpty(channelId) || !channelId.startsWith("UC")) {
                return;
            }

            final YoutubeChannelInfoItemExtractor extractor =
                    new YoutubeChannelInfoItemExtractor(renderer);
            final String name = extractor.getName();
            if (isNullOrEmpty(name)) {
                return;
            }
            final String url = YoutubeChannelLinkHandlerFactory.getInstance()
                    .getUrl("channel/" + channelId);

            String avatarUrl = null;
            try {
                avatarUrl = extractor.getThumbnailUrl();
            } catch (final Exception ignored) {
            }

            long subscriberCount = -1;
            try {
                subscriberCount = extractor.getSubscriberCount();
            } catch (final Exception ignored) {
            }

            String description = null;
            try {
                description = extractor.getDescription();
            } catch (final Exception ignored) {
            }

            out.putIfAbsent(channelId, new YoutubeSubscribedChannel(
                    channelId, url, name, avatarUrl, subscriberCount, description));
        } catch (final Exception ignored) {
        }
    }

    private static void addChannelFromGuideEntry(@Nonnull final JsonObject guideEntry,
                                                 @Nonnull final Map<String, YoutubeSubscribedChannel> out) {
        try {
            final String channelId = getChannelIdFromNavigationEndpoint(
                    guideEntry.getObject("navigationEndpoint"));
            if (isNullOrEmpty(channelId) || !channelId.startsWith("UC")) {
                return;
            }
            final String name = getTextFromObject(guideEntry.getObject("formattedTitle"));
            if (isNullOrEmpty(name)) {
                return;
            }

            String avatarUrl = null;
            final JsonArray thumbnails = guideEntry.getObject("thumbnail").getArray("thumbnails");
            if (!thumbnails.isEmpty()) {
                final String rawUrl = thumbnails.getObject(thumbnails.size() - 1).getString("url");
                if (!isNullOrEmpty(rawUrl)) {
                    avatarUrl = fixThumbnailUrl(rawUrl);
                }
            }

            final String url = YoutubeChannelLinkHandlerFactory.getInstance()
                    .getUrl("channel/" + channelId);
            out.putIfAbsent(channelId, new YoutubeSubscribedChannel(
                    channelId, url, name, avatarUrl, -1, null));
        } catch (final Exception ignored) {
        }
    }

    private static void addChannelFromLockup(@Nonnull final JsonObject lockup,
                                             @Nonnull final Map<String, YoutubeSubscribedChannel> out) {
        try {
            String channelId = lockup.getString("contentId");
            if (isNullOrEmpty(channelId)) {
                final JsonObject onTap = lockup.getObject("rendererContext")
                        .getObject("commandContext")
                        .getObject("onTap")
                        .getObject("innertubeCommand");
                channelId = getChannelIdFromNavigationEndpoint(onTap);
            }
            if (isNullOrEmpty(channelId) || !channelId.startsWith("UC")) {
                return;
            }

            final JsonObject metadata = lockup.getObject("metadata")
                    .getObject("lockupMetadataViewModel");
            final String name = metadata.getObject("title").getString("content");
            if (isNullOrEmpty(name)) {
                return;
            }

            String avatarUrl = null;
            final JsonArray sources = lockup.getObject("contentImage")
                    .getObject("decoratedAvatarViewModel")
                    .getObject("avatar")
                    .getObject("avatarViewModel")
                    .getObject("image")
                    .getArray("sources");
            if (!sources.isEmpty()) {
                final String rawUrl = sources.getObject(sources.size() - 1).getString("url");
                if (!isNullOrEmpty(rawUrl)) {
                    avatarUrl = fixThumbnailUrl(rawUrl);
                }
            }

            final String url = YoutubeChannelLinkHandlerFactory.getInstance()
                    .getUrl("channel/" + channelId);
            out.putIfAbsent(channelId, new YoutubeSubscribedChannel(
                    channelId, url, name, avatarUrl, -1, null));
        } catch (final Exception ignored) {
        }
    }

    private static void collectHistoryItemsRecursive(
            @Nullable final Object node,
            @Nullable final TimeAgoParser timeAgoParser,
            @Nonnull final List<YoutubeHistoryItem> out,
            @Nonnull final Set<String> seenUrls,
            @Nonnull final String[] continuationHolder) {
        if (node instanceof JsonArray) {
            for (final Object element : (JsonArray) node) {
                collectHistoryItemsRecursive(
                        element, timeAgoParser, out, seenUrls, continuationHolder);
            }
            return;
        }
        if (!(node instanceof JsonObject)) {
            return;
        }

        final JsonObject obj = (JsonObject) node;

        if (obj.has("videoRenderer")) {
            final JsonObject video = obj.getObject("videoRenderer");
            commitHistoryStream(
                    new YoutubeStreamInfoItemExtractor(video, timeAgoParser),
                    extractPercentWatchedFromVideoRenderer(video),
                    out, seenUrls);
            return;
        }
        if (obj.has("compactVideoRenderer")) {
            final JsonObject video = obj.getObject("compactVideoRenderer");
            commitHistoryStream(
                    new YoutubeStreamInfoItemExtractor(video, timeAgoParser),
                    extractPercentWatchedFromVideoRenderer(video),
                    out, seenUrls);
            return;
        }
        if (obj.has("gridVideoRenderer")) {
            final JsonObject video = obj.getObject("gridVideoRenderer");
            commitHistoryStream(
                    new YoutubeStreamInfoItemExtractor(video, timeAgoParser),
                    extractPercentWatchedFromVideoRenderer(video),
                    out, seenUrls);
            return;
        }
        if (obj.has("lockupViewModel")) {
            final JsonObject lockup = obj.getObject("lockupViewModel");
            final String contentType = lockup.getString("contentType");
            if ("LOCKUP_CONTENT_TYPE_VIDEO".equals(contentType)
                    || "LOCKUP_CONTENT_TYPE_EPISODE".equals(contentType)) {
                commitHistoryStream(
                        new YoutubeLockupStreamInfoItemExtractor(lockup, timeAgoParser),
                        extractPercentWatchedFromLockup(lockup),
                        out, seenUrls);
                return;
            }
        }
        if (obj.has("shortsLockupViewModel")) {
            final JsonObject shorts = obj.getObject("shortsLockupViewModel");
            commitHistoryStream(
                    new YoutubeShortsInfoItemExtractor(shorts),
                    0,
                    out, seenUrls);
            return;
        }
        if (obj.has("continuationItemRenderer")) {
            final String token = extractTokenFromContinuationItem(
                    obj.getObject("continuationItemRenderer"));
            if (!isNullOrEmpty(token) && isNullOrEmpty(continuationHolder[0])) {
                continuationHolder[0] = token;
            }
            return;
        }

        for (final Map.Entry<String, Object> entry : obj.entrySet()) {
            collectHistoryItemsRecursive(
                    entry.getValue(), timeAgoParser, out, seenUrls, continuationHolder);
        }
    }

    private static void commitHistoryStream(@Nonnull final StreamInfoItemExtractor extractor,
                                            final int percentWatched,
                                            @Nonnull final List<YoutubeHistoryItem> out,
                                            @Nonnull final Set<String> seenUrls) {
        try {
            final StreamInfoItemsCollector collector =
                    new StreamInfoItemsCollector(ServiceList.YouTube.getServiceId());
            collector.commit(extractor);
            if (!collector.getItems().isEmpty()) {
                final StreamInfoItem item = collector.getItems().get(0);
                if (!isNullOrEmpty(item.getUrl()) && seenUrls.add(item.getUrl())) {
                    out.add(new YoutubeHistoryItem(item, percentWatched));
                }
            }
        } catch (final Exception ignored) {
        }
    }

    private static int extractPercentWatchedFromVideoRenderer(@Nonnull final JsonObject video) {
        final JsonArray overlays = video.getArray("thumbnailOverlays");
        for (int i = 0; i < overlays.size(); i++) {
            final JsonObject overlay = overlays.getObject(i);
            if (overlay.has("thumbnailOverlayResumePlaybackRenderer")) {
                return overlay.getObject("thumbnailOverlayResumePlaybackRenderer")
                        .getInt("percentDurationWatched", 0);
            }
        }
        return 0;
    }

    private static int extractPercentWatchedFromLockup(@Nonnull final JsonObject lockup) {
        try {
            final JsonArray overlays = lockup.getObject("contentImage")
                    .getObject("thumbnailViewModel")
                    .getArray("overlays");
            for (int i = 0; i < overlays.size(); i++) {
                final JsonObject overlay = overlays.getObject(i);
                if (overlay.has("thumbnailOverlayProgressBarViewModel")) {
                    return overlay.getObject("thumbnailOverlayProgressBarViewModel")
                            .getInt("startPercent", 0);
                }
                if (overlay.has("thumbnailBottomOverlayViewModel")) {
                    final JsonObject progressBar = overlay
                            .getObject("thumbnailBottomOverlayViewModel")
                            .getObject("progressBar")
                            .getObject("thumbnailOverlayProgressBarViewModel");
                    final int percent = progressBar.getInt("startPercent", 0);
                    if (percent > 0) {
                        return percent;
                    }
                }
            }
        } catch (final Exception ignored) {
        }
        return 0;
    }

    private static void findContinuationTokenRecursive(@Nullable final Object node,
                                                       @Nonnull final String[] holder) {
        if (!isNullOrEmpty(holder[0])) {
            return;
        }
        if (node instanceof JsonArray) {
            for (final Object element : (JsonArray) node) {
                findContinuationTokenRecursive(element, holder);
                if (!isNullOrEmpty(holder[0])) {
                    return;
                }
            }
            return;
        }
        if (!(node instanceof JsonObject)) {
            return;
        }

        final JsonObject obj = (JsonObject) node;
        if (obj.has("continuationItemRenderer")) {
            final String token = extractTokenFromContinuationItem(
                    obj.getObject("continuationItemRenderer"));
            if (!isNullOrEmpty(token)) {
                holder[0] = token;
                return;
            }
        }

        for (final Map.Entry<String, Object> entry : obj.entrySet()) {
            findContinuationTokenRecursive(entry.getValue(), holder);
            if (!isNullOrEmpty(holder[0])) {
                return;
            }
        }
    }

    @Nullable
    public static String extractTokenFromContinuationItem(
            @Nonnull final JsonObject continuationItem) {
        final JsonObject endpoint = continuationItem.getObject("continuationEndpoint");
        final String direct = endpoint.getObject("continuationCommand").getString("token");
        if (!isNullOrEmpty(direct)) {
            return direct;
        }

        final JsonArray commands = endpoint.getObject("commandExecutorCommand")
                .getArray("commands");
        for (int i = 0; i < commands.size(); i++) {
            final String token = commands.getObject(i)
                    .getObject("continuationCommand")
                    .getString("token");
            if (!isNullOrEmpty(token)) {
                return token;
            }
        }
        return null;
    }
}
