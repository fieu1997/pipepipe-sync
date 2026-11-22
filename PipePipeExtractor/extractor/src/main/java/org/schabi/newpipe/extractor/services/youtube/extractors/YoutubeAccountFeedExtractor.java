package org.schabi.newpipe.extractor.services.youtube.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.localization.TimeAgoParser;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeAccountFeedLinkHandlerFactory;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.YOUTUBEI_V1_URL;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getLoggedInJsonPostResponse;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareDesktopJsonBuilder;
import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;

/**
 * Authenticated Kiosk extractor for personalized YouTube feeds:
 * - "Recommended Videos" (browseId = FEwhat_to_watch)
 * - "Account History" (browseId = FEhistory)
 */
public class YoutubeAccountFeedExtractor extends KioskExtractor<StreamInfoItem> {
    private JsonObject initialData;

    public YoutubeAccountFeedExtractor(final StreamingService streamingService,
                                       final ListLinkHandler linkHandler,
                                       final String kioskId) {
        super(streamingService, linkHandler, kioskId);
    }

    private String getBrowseId() {
        if (YoutubeAccountFeedLinkHandlerFactory.ACCOUNT_HISTORY_ID.equals(getId())) {
            return YoutubeAccountSyncExtractor.BROWSE_ID_HISTORY;
        }
        return YoutubeAccountSyncExtractor.BROWSE_ID_HOME;
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        if (!ServiceList.YouTube.hasTokens()) {
            throw new ContentNotAvailableException(
                    "Please log in to your YouTube account in Settings > Account > YouTube.");
        }

        final byte[] body = JsonWriter.string(
                prepareDesktopJsonBuilder(getExtractorLocalization(), getExtractorContentCountry())
                        .value("browseId", getBrowseId())
                        .done())
                .getBytes(StandardCharsets.UTF_8);

        initialData = getLoggedInJsonPostResponse("browse", body, getExtractorLocalization());
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        return getId();
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage()
            throws IOException, ExtractionException {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        final String nextToken = parseStreamItemsFromResponse(
                initialData, collector, getTimeAgoParser());

        applyBlockingIfNeeded(collector);
        return new InfoItemsPage<>(collector, buildNextPage(nextToken));
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        if (page == null || isNullOrEmpty(page.getId())) {
            return InfoItemsPage.emptyPage();
        }
        if (!ServiceList.YouTube.hasTokens()) {
            return InfoItemsPage.emptyPage();
        }

        final byte[] body = JsonWriter.string(
                prepareDesktopJsonBuilder(getExtractorLocalization(), getExtractorContentCountry())
                        .value("continuation", page.getId())
                        .done())
                .getBytes(StandardCharsets.UTF_8);

        final JsonObject response = getLoggedInJsonPostResponse(
                "browse", body, getExtractorLocalization());

        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        final String nextToken = parseStreamItemsFromResponse(
                response, collector, getTimeAgoParser());

        applyBlockingIfNeeded(collector);
        return new InfoItemsPage<>(collector, buildNextPage(nextToken));
    }

    private void applyBlockingIfNeeded(@Nonnull final StreamInfoItemsCollector collector) {
        if (YoutubeAccountFeedLinkHandlerFactory.RECOMMENDED_VIDEOS_ID.equals(getId())
                && ServiceList.YouTube.getFilterTypes().contains("recommendations")
                && ServiceList.YouTube.getFilterConfig() != null) {
            collector.applyBlocking(ServiceList.YouTube.getFilterConfig());
        }
    }

    @Nullable
    private Page buildNextPage(@Nullable final String continuationToken) {
        if (isNullOrEmpty(continuationToken)) {
            return null;
        }
        return new Page(
                YOUTUBEI_V1_URL + "browse?" + DISABLE_PRETTY_PRINT_PARAMETER,
                continuationToken);
    }

    /**
     * Parses stream items and extracts the continuation token from any YouTube Home/History
     * InnerTube browse response (both initial load and continuation actions).
     */
    @Nullable
    public static String parseStreamItemsFromResponse(
            @Nonnull final JsonObject response,
            @Nonnull final StreamInfoItemsCollector collector,
            @Nullable final TimeAgoParser timeAgoParser) {
        final Set<String> seenUrls = new HashSet<>();
        final String[] continuationHolder = new String[1];

        if (response.has("onResponseReceivedActions")) {
            final JsonArray actions = response.getArray("onResponseReceivedActions");
            for (int i = 0; i < actions.size(); i++) {
                final JsonObject action = actions.getObject(i);
                if (action.has("appendContinuationItemsAction")) {
                    collectStreamsRecursive(
                            action.getObject("appendContinuationItemsAction")
                                    .getArray("continuationItems"),
                            collector, timeAgoParser, seenUrls, continuationHolder);
                } else if (action.has("reloadContinuationItemsCommand")) {
                    collectStreamsRecursive(
                            action.getObject("reloadContinuationItemsCommand")
                                    .getArray("continuationItems"),
                            collector, timeAgoParser, seenUrls, continuationHolder);
                }
            }
            return continuationHolder[0];
        }

        final JsonObject rootContent = findSelectedTabContent(response);
        collectStreamsRecursive(
                rootContent != null ? rootContent : response,
                collector, timeAgoParser, seenUrls, continuationHolder);
        return continuationHolder[0];
    }

    @Nullable
    private static JsonObject findSelectedTabContent(@Nonnull final JsonObject response) {
        final JsonArray tabs = response.getObject("contents")
                .getObject("twoColumnBrowseResultsRenderer")
                .getArray("tabs");
        for (int i = 0; i < tabs.size(); i++) {
            final JsonObject tabRenderer = tabs.getObject(i).getObject("tabRenderer");
            if (tabRenderer.getBoolean("selected", false) && tabRenderer.has("content")) {
                return tabRenderer.getObject("content");
            }
        }
        if (!tabs.isEmpty()) {
            final JsonObject firstTab = tabs.getObject(0).getObject("tabRenderer");
            if (firstTab.has("content")) {
                return firstTab.getObject("content");
            }
        }
        return null;
    }

    private static void collectStreamsRecursive(
            @Nullable final Object node,
            @Nonnull final StreamInfoItemsCollector collector,
            @Nullable final TimeAgoParser timeAgoParser,
            @Nonnull final Set<String> seenUrls,
            @Nonnull final String[] continuationHolder) {
        if (node instanceof JsonArray) {
            for (final Object element : (JsonArray) node) {
                collectStreamsRecursive(
                        element, collector, timeAgoParser, seenUrls, continuationHolder);
            }
            return;
        }
        if (!(node instanceof JsonObject)) {
            return;
        }

        final JsonObject obj = (JsonObject) node;

        // Skip sponsored / promoted ad slots on YouTube Home Feed
        if (obj.has("adSlotRenderer") || obj.has("promotedSparklesWebRenderer")
                || obj.has("displayAdRenderer")) {
            return;
        }

        if (obj.has("videoRenderer")) {
            commitIfUnique(
                    new YoutubeStreamInfoItemExtractor(
                            obj.getObject("videoRenderer"), timeAgoParser),
                    collector, seenUrls);
            return;
        }
        if (obj.has("gridVideoRenderer")) {
            commitIfUnique(
                    new YoutubeStreamInfoItemExtractor(
                            obj.getObject("gridVideoRenderer"), timeAgoParser),
                    collector, seenUrls);
            return;
        }
        if (obj.has("compactVideoRenderer")) {
            commitIfUnique(
                    new YoutubeStreamInfoItemExtractor(
                            obj.getObject("compactVideoRenderer"), timeAgoParser),
                    collector, seenUrls);
            return;
        }
        if (obj.has("lockupViewModel")) {
            final JsonObject lockup = obj.getObject("lockupViewModel");
            final String contentType = lockup.getString("contentType");
            if ("LOCKUP_CONTENT_TYPE_VIDEO".equals(contentType)
                    || "LOCKUP_CONTENT_TYPE_EPISODE".equals(contentType)) {
                commitIfUnique(
                        new YoutubeLockupStreamInfoItemExtractor(lockup, timeAgoParser),
                        collector, seenUrls);
                return;
            }
            return;
        }
        if (obj.has("shortsLockupViewModel")) {
            commitIfUnique(
                    new YoutubeShortsInfoItemExtractor(obj.getObject("shortsLockupViewModel")),
                    collector, seenUrls);
            return;
        }
        if (obj.has("reelItemRenderer")) {
            commitIfUnique(
                    new YoutubeStreamInfoItemExtractor(
                            obj.getObject("reelItemRenderer"), timeAgoParser),
                    collector, seenUrls);
            return;
        }
        if (obj.has("continuationItemRenderer")) {
            final String token = YoutubeAccountSyncExtractor.extractTokenFromContinuationItem(
                    obj.getObject("continuationItemRenderer"));
            if (!isNullOrEmpty(token) && isNullOrEmpty(continuationHolder[0])) {
                continuationHolder[0] = token;
            }
            return;
        }

        for (final Map.Entry<String, Object> entry : obj.entrySet()) {
            collectStreamsRecursive(
                    entry.getValue(), collector, timeAgoParser, seenUrls, continuationHolder);
        }
    }

    private static void commitIfUnique(
            @Nonnull final org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor extractor,
            @Nonnull final StreamInfoItemsCollector collector,
            @Nonnull final Set<String> seenUrls) {
        try {
            final String url = extractor.getUrl();
            if (!isNullOrEmpty(url) && seenUrls.add(url)) {
                collector.commit(extractor);
            }
        } catch (final Exception ignored) {
        }
    }
}
