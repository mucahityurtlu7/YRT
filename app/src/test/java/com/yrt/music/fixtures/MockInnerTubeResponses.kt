package com.yrt.music.fixtures

/**
 * Realistic mock JSON payloads matching YouTube Music InnerTube WEB_REMIX endpoint responses.
 * Self-contained for fast, deterministic, offline unit testing.
 */
object MockInnerTubeResponses {

    val ARTIST_SEARCH_JSON = """
    {
      "contents": {
        "tabbedSearchResultsRenderer": {
          "tabs": [
            {
              "tabRenderer": {
                "content": {
                  "sectionListRenderer": {
                    "contents": [
                      {
                        "musicShelfRenderer": {
                          "title": { "runs": [ { "text": "Artists" } ] },
                          "contents": [
                            {
                              "musicResponsiveListItemRenderer": {
                                "navigationEndpoint": {
                                  "browseEndpoint": {
                                    "browseId": "UCRr1xG_2WIDs18a6cIiCxeA"
                                  }
                                },
                                "flexColumns": [
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {
                                        "runs": [ { "text": "Daft Punk" } ]
                                      }
                                    }
                                  },
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {
                                        "runs": [ { "text": "Artist • 8.1M subscribers" } ]
                                      }
                                    }
                                  }
                                ],
                                "thumbnail": {
                                  "musicThumbnailRenderer": {
                                    "thumbnail": {
                                      "thumbnails": [
                                        { "url": "https://lh3.googleusercontent.com/daftpunk_thumb.jpg", "width": 226, "height": 226 }
                                      ]
                                    }
                                  }
                                }
                              }
                            },
                            {
                              "musicResponsiveListItemRenderer": {
                                "navigationEndpoint": {
                                  "browseEndpoint": {
                                    "browseId": "UCr_iyUANcn9OX_yy9piYoLw"
                                  }
                                },
                                "flexColumns": [
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {
                                        "runs": [ { "text": "Radiohead" } ]
                                      }
                                    }
                                  },
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {
                                        "runs": [ { "text": "Artist • 5.2M subscribers" } ]
                                      }
                                    }
                                  }
                                ],
                                "thumbnail": {
                                  "musicThumbnailRenderer": {
                                    "thumbnail": {
                                      "thumbnails": [
                                        { "url": "https://lh3.googleusercontent.com/radiohead_thumb.jpg", "width": 226, "height": 226 }
                                      ]
                                    }
                                  }
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
    """.trimIndent()

    val ALBUM_SEARCH_JSON = """
    {
      "contents": {
        "tabbedSearchResultsRenderer": {
          "tabs": [
            {
              "tabRenderer": {
                "content": {
                  "sectionListRenderer": {
                    "contents": [
                      {
                        "musicShelfRenderer": {
                          "title": { "runs": [ { "text": "Albums" } ] },
                          "contents": [
                            {
                              "musicResponsiveListItemRenderer": {
                                "navigationEndpoint": {
                                  "browseEndpoint": {
                                    "browseId": "MPREb_yXhSI4FCUo6"
                                  }
                                },
                                "flexColumns": [
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {
                                        "runs": [ { "text": "Random Access Memories" } ]
                                      }
                                    }
                                  },
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {
                                        "runs": [
                                          { "text": "Album" },
                                          { "text": " • " },
                                          { "text": "Daft Punk" },
                                          { "text": " • " },
                                          { "text": "2013" }
                                        ]
                                      }
                                    }
                                  }
                                ],
                                "thumbnail": {
                                  "musicThumbnailRenderer": {
                                    "thumbnail": {
                                      "thumbnails": [
                                        { "url": "https://lh3.googleusercontent.com/ram_cover.jpg", "width": 544, "height": 544 }
                                      ]
                                    }
                                  }
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
    """.trimIndent()

    val ARTIST_PROFILE_JSON = """
    {
      "header": {
        "musicImmersiveHeaderRenderer": {
          "title": { "runs": [ { "text": "Daft Punk" } ] },
          "description": { "runs": [ { "text": "Legendary French electronic music duo." } ] },
          "subscriptionButton": {
            "subscribeButtonRenderer": {
              "subscriberCountText": { "runs": [ { "text": "8.1M subscribers" } ] }
            }
          },
          "thumbnail": {
            "musicThumbnailRenderer": {
              "thumbnail": {
                "thumbnails": [
                  { "url": "https://lh3.googleusercontent.com/daftpunk_hero.jpg", "width": 1200, "height": 800 }
                ]
              }
            }
          }
        }
      },
      "contents": {
        "singleColumnBrowseResultsRenderer": {
          "tabs": [
            {
              "tabRenderer": {
                "content": {
                  "sectionListRenderer": {
                    "contents": [
                      {
                        "musicShelfRenderer": {
                          "title": { "runs": [ { "text": "Songs" } ] },
                          "contents": [
                            {
                              "musicResponsiveListItemRenderer": {
                                "playlistItemData": { "videoId": "5NV6Rdv1a3I" },
                                "flexColumns": [
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": { "runs": [ { "text": "Get Lucky" } ] }
                                    }
                                  },
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": { "runs": [ { "text": "Daft Punk" } ] }
                                    }
                                  }
                                ],
                                "fixedColumns": [
                                  {
                                    "musicResponsiveListItemFixedColumnRenderer": {
                                      "text": { "runs": [ { "text": "4:08" } ] }
                                    }
                                  }
                                ],
                                "thumbnail": {
                                  "musicThumbnailRenderer": {
                                    "thumbnail": {
                                      "thumbnails": [
                                        { "url": "https://lh3.googleusercontent.com/getlucky.jpg", "width": 226, "height": 226 }
                                      ]
                                    }
                                  }
                                }
                              }
                            },
                            {
                              "musicResponsiveListItemRenderer": {
                                "playlistItemData": { "videoId": "FGBhQbmMxH8" },
                                "flexColumns": [
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": { "runs": [ { "text": "One More Time" } ] }
                                    }
                                  },
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": { "runs": [ { "text": "Daft Punk" } ] }
                                    }
                                  }
                                ],
                                "fixedColumns": [
                                  {
                                    "musicResponsiveListItemFixedColumnRenderer": {
                                      "text": { "runs": [ { "text": "5:20" } ] }
                                    }
                                  }
                                ],
                                "thumbnail": {
                                  "musicThumbnailRenderer": {
                                    "thumbnail": {
                                      "thumbnails": [
                                        { "url": "https://lh3.googleusercontent.com/onemoretime.jpg", "width": 226, "height": 226 }
                                      ]
                                    }
                                  }
                                }
                              }
                            }
                          ]
                        }
                      },
                      {
                        "musicCarouselShelfRenderer": {
                          "header": {
                            "musicCarouselShelfBasicHeaderRenderer": {
                              "title": { "runs": [ { "text": "Albums" } ] }
                            }
                          },
                          "contents": [
                            {
                              "musicTwoRowItemRenderer": {
                                "navigationEndpoint": {
                                  "browseEndpoint": {
                                    "browseId": "MPREb_yXhSI4FCUo6"
                                  }
                                },
                                "title": { "runs": [ { "text": "Random Access Memories" } ] },
                                "subtitle": { "runs": [ { "text": "2013" }, { "text": " • " }, { "text": "Album" } ] },
                                "thumbnailRenderer": {
                                  "musicThumbnailRenderer": {
                                    "thumbnail": {
                                      "thumbnails": [
                                        { "url": "https://lh3.googleusercontent.com/ram.jpg", "width": 544, "height": 544 }
                                      ]
                                    }
                                  }
                                }
                              }
                            },
                            {
                              "musicTwoRowItemRenderer": {
                                "navigationEndpoint": {
                                  "browseEndpoint": {
                                    "browseId": "MPREb_Discovery123"
                                  }
                                },
                                "title": { "runs": [ { "text": "Discovery" } ] },
                                "subtitle": { "runs": [ { "text": "2001" }, { "text": " • " }, { "text": "Album" } ] },
                                "thumbnailRenderer": {
                                  "musicThumbnailRenderer": {
                                    "thumbnail": {
                                      "thumbnails": [
                                        { "url": "https://lh3.googleusercontent.com/discovery.jpg", "width": 544, "height": 544 }
                                      ]
                                    }
                                  }
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
    """.trimIndent()

    val ALBUM_DETAILS_JSON = """
    {
      "header": {
        "musicDetailHeaderRenderer": {
          "title": { "runs": [ { "text": "Random Access Memories" } ] },
          "subtitle": {
            "runs": [
              { "text": "Album" },
              { "text": " • " },
              { "text": "Daft Punk" },
              { "text": " • " },
              { "text": "2013" }
            ]
          },
          "secondSubtitle": { "runs": [ { "text": "13 songs • 1 hour 14 minutes" } ] },
          "thumbnail": {
            "croppedMusicThumbnailRenderer": {
              "thumbnail": {
                "thumbnails": [
                  { "url": "https://lh3.googleusercontent.com/ram_large.jpg", "width": 1000, "height": 1000 }
                ]
              }
            }
          }
        }
      },
      "contents": {
        "twoColumnBrowseResultsRenderer": {
          "secondaryContents": {
            "sectionListRenderer": {
              "contents": [
                {
                  "musicShelfRenderer": {
                    "contents": [
                      {
                        "musicResponsiveListItemRenderer": {
                          "index": { "runs": [ { "text": "1" } ] },
                          "playlistItemData": { "videoId": "zhl-Cs1-sG4" },
                          "flexColumns": [
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [ { "text": "Give Life Back to Music" } ] }
                              }
                            },
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [ { "text": "Daft Punk" } ] }
                              }
                            }
                          ],
                          "fixedColumns": [
                            {
                              "musicResponsiveListItemFixedColumnRenderer": {
                                "text": { "runs": [ { "text": "4:34" } ] }
                              }
                            }
                          ]
                        }
                      },
                      {
                        "musicResponsiveListItemRenderer": {
                          "index": { "runs": [ { "text": "8" } ] },
                          "playlistItemData": { "videoId": "5NV6Rdv1a3I" },
                          "flexColumns": [
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [ { "text": "Get Lucky" } ] }
                              }
                            },
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [ { "text": "Daft Punk" } ] }
                              }
                            }
                          ],
                          "fixedColumns": [
                            {
                              "musicResponsiveListItemFixedColumnRenderer": {
                                "text": { "runs": [ { "text": "4:08" } ] }
                              }
                            }
                          ]
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
    }
    """.trimIndent()

    val EMPTY_RESPONSE_JSON = """{ "contents": {} }"""
}
