package org.schabi.newpipe.extractor.services.youtube;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.CancellableCall;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.kiosk.KioskList;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.localization.TimeAgoPatternsManager;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeAccountFeedExtractor;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeAccountSyncExtractor;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeAccountFeedLinkHandlerFactory;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YoutubeAccountSyncTest {

    @BeforeEach
    void setUp() {
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(final Request request) {
                throw new UnsupportedOperationException("Network disabled in unit tests");
            }

            @Override
            public CancellableCall executeAsync(final Request request, final AsyncCallback callback) {
                throw new UnsupportedOperationException("Network disabled in unit tests");
            }
        });
    }

    @AfterEach
    void tearDown() {
        ServiceList.YouTube.setTokens(null);
    }

    @Test
    void parseSubscribedChannelsHandlesChannelRendererLockupAndContinuation() throws Exception {
        final String json = """
                {
                  "contents": {
                    "twoColumnBrowseResultsRenderer": {
                      "tabs": [
                        {
                          "tabRenderer": {
                            "selected": true,
                            "content": {
                              "sectionListRenderer": {
                                "contents": [
                                  {
                                    "itemSectionRenderer": {
                                      "contents": [
                                        {
                                          "channelRenderer": {
                                            "channelId": "UC1234567890123456789012",
                                            "title": { "simpleText": "Alpha Channel" },
                                            "thumbnail": { "thumbnails": [{ "url": "//yt3.ggpht.com/a1.jpg" }] },
                                            "subscriberCountText": { "simpleText": "1.2M subscribers" },
                                            "descriptionSnippet": { "runs": [{ "text": "Tech videos" }] }
                                          }
                                        },
                                        {
                                          "lockupViewModel": {
                                            "contentId": "UC9876543210987654321098",
                                            "contentType": "LOCKUP_CONTENT_TYPE_CHANNEL",
                                            "metadata": {
                                              "lockupMetadataViewModel": {
                                                "title": { "content": "Beta Lockup" }
                                              }
                                            },
                                            "contentImage": {
                                              "decoratedAvatarViewModel": {
                                                "avatar": {
                                                  "avatarViewModel": {
                                                    "image": {
                                                      "sources": [{ "url": "https://yt3.ggpht.com/b1.jpg" }]
                                                    }
                                                  }
                                                }
                                              }
                                            }
                                          }
                                        },
                                        {
                                          "continuationItemRenderer": {
                                            "continuationEndpoint": {
                                              "continuationCommand": { "token": "NEXT_SUB_TOKEN" }
                                            }
                                          }
                                        }
                                      ]
                                    }
                                  }
                                ]
                              }
                            }
                          }
                        }
                      ]
                    }
                  }
                }
                """;

        final JsonObject root = JsonParser.object().from(json);
        final YoutubeAccountSyncExtractor.SubscriptionsPageResult result =
                YoutubeAccountSyncExtractor.parseSubscribedChannelsFromJson(root);

        final List<YoutubeAccountSyncExtractor.YoutubeSubscribedChannel> channels = result.getChannels();
        assertEquals(2, channels.size());
        assertEquals("UC1234567890123456789012", channels.get(0).getChannelId());
        assertEquals("Alpha Channel", channels.get(0).getName());
        assertEquals("https://www.youtube.com/channel/UC1234567890123456789012", channels.get(0).getUrl());
        assertEquals("https://yt3.ggpht.com/a1.jpg", channels.get(0).getAvatarUrl());
        assertEquals(1_200_000L, channels.get(0).getSubscriberCount());
        assertEquals("Tech videos", channels.get(0).getDescription());

        assertEquals("UC9876543210987654321098", channels.get(1).getChannelId());
        assertEquals("Beta Lockup", channels.get(1).getName());
        assertEquals("https://yt3.ggpht.com/b1.jpg", channels.get(1).getAvatarUrl());
        assertEquals("NEXT_SUB_TOKEN", result.getContinuationToken());
    }

    @Test
    void parseHistoryExtractsVideoRendererLockupProgressAndContinuation() throws Exception {
        final String json = """
                {
                  "contents": {
                    "twoColumnBrowseResultsRenderer": {
                      "tabs": [
                        {
                          "tabRenderer": {
                            "selected": true,
                            "content": {
                              "sectionListRenderer": {
                                "contents": [
                                  {
                                    "itemSectionRenderer": {
                                      "contents": [
                                        {
                                          "videoRenderer": {
                                            "videoId": "dQw4w9WgXcQ",
                                            "title": { "runs": [{ "text": "History Video 1" }] },
                                            "lengthText": { "simpleText": "3:33" },
                                            "viewCountText": { "simpleText": "1,000 views" },
                                            "ownerText": {
                                              "runs": [
                                                {
                                                  "text": "Channel One",
                                                  "navigationEndpoint": {
                                                    "browseEndpoint": {
                                                      "browseId": "UC1234567890123456789012",
                                                      "canonicalBaseUrl": "/channel/UC1234567890123456789012"
                                                    }
                                                  }
                                                }
                                              ]
                                            },
                                            "thumbnail": {
                                              "thumbnails": [{ "url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg" }]
                                            },
                                            "thumbnailOverlays": [
                                              { "thumbnailOverlayResumePlaybackRenderer": { "percentDurationWatched": 65 } }
                                            ]
                                          }
                                        },
                                        {
                                          "lockupViewModel": {
                                            "contentId": "abc123XYZ89",
                                            "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                                            "contentImage": {
                                              "thumbnailViewModel": {
                                                "image": {
                                                  "sources": [{ "url": "https://i.ytimg.com/vi/abc123XYZ89/hqdefault.jpg" }]
                                                },
                                                "overlays": [
                                                  {
                                                    "thumbnailOverlayBadgeViewModel": {
                                                      "thumbnailBadges": [{ "thumbnailBadgeViewModel": { "text": "10:05" } }]
                                                    }
                                                  },
                                                  { "thumbnailOverlayProgressBarViewModel": { "startPercent": 42 } }
                                                ]
                                              }
                                            },
                                            "metadata": {
                                              "lockupMetadataViewModel": {
                                                "title": { "content": "History Video 2" },
                                                "metadata": {
                                                  "contentMetadataViewModel": {
                                                    "metadataRows": [
                                                      {
                                                        "metadataParts": [
                                                          {
                                                            "text": {
                                                              "content": "Channel Two",
                                                              "commandRuns": [
                                                                {
                                                                  "onTap": {
                                                                    "innertubeCommand": {
                                                                      "browseEndpoint": {
                                                                        "browseId": "UC9876543210987654321098",
                                                                        "canonicalBaseUrl": "/channel/UC9876543210987654321098"
                                                                      }
                                                                    }
                                                                  }
                                                                }
                                                              ]
                                                            }
                                                          }
                                                        ]
                                                      },
                                                      {
                                                        "metadataParts": [{ "text": { "content": "5K views" } }]
                                                      }
                                                    ]
                                                  }
                                                }
                                              }
                                            }
                                          }
                                        },
                                        {
                                          "continuationItemRenderer": {
                                            "continuationEndpoint": {
                                              "continuationCommand": { "token": "HIST_NEXT_1" }
                                            }
                                          }
                                        }
                                      ]
                                    }
                                  }
                                ]
                              }
                            }
                          }
                        }
                      ]
                    }
                  }
                }
                """;

        final JsonObject root = JsonParser.object().from(json);
        final YoutubeAccountSyncExtractor.HistoryPageResult result =
                YoutubeAccountSyncExtractor.parseHistoryPageFromJson(
                        root, TimeAgoPatternsManager.getTimeAgoParserFor(Localization.DEFAULT));

        assertEquals(2, result.getItems().size());
        assertEquals("History Video 1", result.getItems().get(0).getStreamInfoItem().getName());
        assertEquals(65, result.getItems().get(0).getPercentWatched());
        assertEquals(213L, result.getItems().get(0).getStreamInfoItem().getDuration());

        assertEquals("History Video 2", result.getItems().get(1).getStreamInfoItem().getName());
        assertEquals(42, result.getItems().get(1).getPercentWatched());
        assertEquals(605L, result.getItems().get(1).getStreamInfoItem().getDuration());
        assertEquals("HIST_NEXT_1", result.getContinuationToken());
    }

    @Test
    void parseHomeRecommendationsAndContinuationSkipsAds() throws Exception {
        final String initialJson = """
                {
                  "contents": {
                    "twoColumnBrowseResultsRenderer": {
                      "tabs": [
                        {
                          "tabRenderer": {
                            "selected": true,
                            "content": {
                              "richGridRenderer": {
                                "contents": [
                                  {
                                    "richItemRenderer": {
                                      "content": {
                                        "adSlotRenderer": { "adSlotMetadata": { "slotId": "ad1" } }
                                      }
                                    }
                                  },
                                  {
                                    "richItemRenderer": {
                                      "content": {
                                        "videoRenderer": {
                                          "videoId": "vidRec00001",
                                          "title": { "runs": [{ "text": "Recommended Video" }] },
                                          "lengthText": { "simpleText": "5:00" },
                                          "viewCountText": { "simpleText": "12,345 views" },
                                          "ownerText": {
                                            "runs": [
                                              {
                                                "text": "Creator A",
                                                "navigationEndpoint": {
                                                  "browseEndpoint": {
                                                    "browseId": "UC1234567890123456789012",
                                                    "canonicalBaseUrl": "/channel/UC1234567890123456789012"
                                                  }
                                                }
                                              }
                                            ]
                                          },
                                          "thumbnail": {
                                            "thumbnails": [{ "url": "https://i.ytimg.com/vi/vidRec00001/hqdefault.jpg" }]
                                          }
                                        }
                                      }
                                    }
                                  },
                                  {
                                    "continuationItemRenderer": {
                                      "continuationEndpoint": {
                                        "continuationCommand": { "token": "HOME_CONT_1" }
                                      }
                                    }
                                  }
                                ]
                              }
                            }
                          }
                        }
                      ]
                    }
                  }
                }
                """;

        final JsonObject root = JsonParser.object().from(initialJson);
        final StreamInfoItemsCollector collector =
                new StreamInfoItemsCollector(ServiceList.YouTube.getServiceId());
        final String token = YoutubeAccountFeedExtractor.parseStreamItemsFromResponse(
                root, collector, TimeAgoPatternsManager.getTimeAgoParserFor(Localization.DEFAULT));

        assertEquals(1, collector.getItems().size());
        assertEquals("Recommended Video", collector.getItems().get(0).getName());
        assertEquals("HOME_CONT_1", token);

        final String continuationJson = """
                {
                  "onResponseReceivedActions": [
                    {
                      "appendContinuationItemsAction": {
                        "continuationItems": [
                          {
                            "richItemRenderer": {
                              "content": {
                                "videoRenderer": {
                                  "videoId": "vidRec00002",
                                  "title": { "runs": [{ "text": "Recommended Page 2" }] },
                                  "lengthText": { "simpleText": "2:10" },
                                  "viewCountText": { "simpleText": "999 views" },
                                  "ownerText": {
                                    "runs": [
                                      {
                                        "text": "Creator B",
                                        "navigationEndpoint": {
                                          "browseEndpoint": {
                                            "browseId": "UC9876543210987654321098",
                                            "canonicalBaseUrl": "/channel/UC9876543210987654321098"
                                          }
                                        }
                                      }
                                    ]
                                  },
                                  "thumbnail": {
                                    "thumbnails": [{ "url": "https://i.ytimg.com/vi/vidRec00002/hqdefault.jpg" }]
                                  }
                                }
                              }
                            }
                          },
                          {
                            "continuationItemRenderer": {
                              "continuationEndpoint": {
                                "continuationCommand": { "token": "HOME_CONT_2" }
                              }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        final StreamInfoItemsCollector page2Collector =
                new StreamInfoItemsCollector(ServiceList.YouTube.getServiceId());
        final String token2 = YoutubeAccountFeedExtractor.parseStreamItemsFromResponse(
                JsonParser.object().from(continuationJson),
                page2Collector,
                TimeAgoPatternsManager.getTimeAgoParserFor(Localization.DEFAULT));

        assertEquals(1, page2Collector.getItems().size());
        assertEquals("Recommended Page 2", page2Collector.getItems().get(0).getName());
        assertEquals("HOME_CONT_2", token2);
    }

    @Test
    void accountFeedLinkHandlerDefaultKioskAndCookieParsingWork() throws Exception {
        final YoutubeAccountFeedLinkHandlerFactory factory =
                new YoutubeAccountFeedLinkHandlerFactory();
        assertTrue(factory.onAcceptUrl(YoutubeAccountFeedLinkHandlerFactory.RECOMMENDED_VIDEOS_URL));
        assertTrue(factory.onAcceptUrl(YoutubeAccountFeedLinkHandlerFactory.ACCOUNT_HISTORY_URL));
        assertFalse(factory.onAcceptUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals(YoutubeAccountFeedLinkHandlerFactory.RECOMMENDED_VIDEOS_ID,
                factory.getId(YoutubeAccountFeedLinkHandlerFactory.RECOMMENDED_VIDEOS_URL));
        assertEquals(YoutubeAccountFeedLinkHandlerFactory.ACCOUNT_HISTORY_ID,
                factory.getId(YoutubeAccountFeedLinkHandlerFactory.ACCOUNT_HISTORY_URL));

        final Map<String, String> parsedCookies =
                YoutubeParsingHelper.parseCookies("SID=abc; SAPISID=def; ");
        assertEquals("abc", parsedCookies.get("SID"));
        assertEquals("def", parsedCookies.get("SAPISID"));

        ServiceList.YouTube.setTokens(null);
        final KioskList loggedOutKiosks = ServiceList.YouTube.getKioskList();
        assertEquals("Recommended Lives", loggedOutKiosks.getDefaultKioskId());
        assertNotNull(loggedOutKiosks.getExtractorById(
                YoutubeAccountFeedLinkHandlerFactory.RECOMMENDED_VIDEOS_ID, null));

        ServiceList.YouTube.setTokens("SID=abc; SAPISID=def;");
        final KioskList loggedInKiosks = ServiceList.YouTube.getKioskList();
        assertEquals(YoutubeAccountFeedLinkHandlerFactory.RECOMMENDED_VIDEOS_ID,
                loggedInKiosks.getDefaultKioskId());
        assertNotNull(loggedInKiosks.getExtractorById(
                YoutubeAccountFeedLinkHandlerFactory.ACCOUNT_HISTORY_ID, null));
    }
}
