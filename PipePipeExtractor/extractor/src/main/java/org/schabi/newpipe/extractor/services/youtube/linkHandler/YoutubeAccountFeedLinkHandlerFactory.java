package org.schabi.newpipe.extractor.services.youtube.linkHandler;

import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.utils.Utils;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isInvidiousURL;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isYoutubeURL;

public class YoutubeAccountFeedLinkHandlerFactory extends ListLinkHandlerFactory {
    public static final String RECOMMENDED_VIDEOS_ID = "Recommended Videos";
    public static final String ACCOUNT_HISTORY_ID = "Account History";

    public static final String RECOMMENDED_VIDEOS_URL = "https://www.youtube.com/feed/recommended";
    public static final String ACCOUNT_HISTORY_URL = "https://www.youtube.com/feed/history";

    @Override
    public String getUrl(final String id,
                         final List<FilterItem> contentFilters,
                         final List<FilterItem> sortFilter) {
        if (ACCOUNT_HISTORY_ID.equals(id)) {
            return ACCOUNT_HISTORY_URL;
        }
        return RECOMMENDED_VIDEOS_URL;
    }

    @Override
    public String getId(final String url) {
        if (url != null && url.contains("/feed/history")) {
            return ACCOUNT_HISTORY_ID;
        }
        return RECOMMENDED_VIDEOS_ID;
    }

    @Override
    public boolean onAcceptUrl(final String url) {
        final URL urlObj;
        try {
            urlObj = Utils.stringToURL(url);
        } catch (final MalformedURLException e) {
            return false;
        }

        final String urlPath = urlObj.getPath();
        return Utils.isHTTP(urlObj)
                && (isYoutubeURL(urlObj) || isInvidiousURL(urlObj))
                && ("/feed/recommended".equals(urlPath) || "/feed/history".equals(urlPath));
    }
}
