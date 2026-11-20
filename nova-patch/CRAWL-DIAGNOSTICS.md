# Nova crawl failure diagnostics

Run: 44810790179

## nova-patch/nova-tv/tools/server-selftest.log
```
test_a_failed_page_is_retried_not_called_empty (__main__.CrawlerBehaviour.test_a_failed_page_is_retried_not_called_empty) ... ok
test_a_short_page_has_no_follow_up (__main__.CrawlerBehaviour.test_a_short_page_has_no_follow_up) ... ok
test_catalog_only_manifest_is_refused_not_counted (__main__.CrawlerBehaviour.test_catalog_only_manifest_is_refused_not_counted) ... ok
test_failed_community_page_is_actually_fetched_again (__main__.CrawlerBehaviour.test_failed_community_page_is_actually_fetched_again) ... ok
test_homepage_and_pages_are_probed (__main__.CrawlerBehaviour.test_homepage_and_pages_are_probed) ... ok
test_inline_directory_manifest_needs_no_second_request (__main__.CrawlerBehaviour.test_inline_directory_manifest_needs_no_second_request) ... ok
test_json_document_manifests_are_found_at_depth (__main__.CrawlerBehaviour.test_json_document_manifests_are_found_at_depth) ... ok
test_malformed_directory_items_do_not_abort_page (__main__.CrawlerBehaviour.test_malformed_directory_items_do_not_abort_page) ... ok
test_manifest_worker_bug_is_not_reported_as_an_empty_source (__main__.CrawlerBehaviour.test_manifest_worker_bug_is_not_reported_as_an_empty_source) ... ok
test_readme_links_become_servers (__main__.CrawlerBehaviour.test_readme_links_become_servers) ... ok
test_a_full_page_enqueues_the_next_page (__main__.LoopSignals.test_a_full_page_enqueues_the_next_page) ... ok
test_frontier_seeds_every_github_query_and_page (__main__.LoopSignals.test_frontier_seeds_every_github_query_and_page) ... ok
test_keys_match_the_app (__main__.ManifestRules.test_keys_match_the_app) ... ok
test_malformed_public_manifest_is_refused_not_a_crash (__main__.ManifestRules.test_malformed_public_manifest_is_refused_not_a_crash) ... ok
test_stream_resource_required (__main__.ManifestRules.test_stream_resource_required) ... ok
test_caps_at_target (__main__.PoolRules.test_caps_at_target) ... ok
test_dedupes_the_same_server (__main__.PoolRules.test_dedupes_the_same_server) ... ok
test_per_host_cap (__main__.PoolRules.test_per_host_cap) ... ok
test_retired_never_returns (__main__.PoolRules.test_retired_never_returns) ... ok
test_github_limiter_wont_wait_past_deadline (__main__.ThreadingAndSecurity.test_github_limiter_wont_wait_past_deadline) ... ok
test_redirect_to_private_or_http_address_is_blocked (__main__.ThreadingAndSecurity.test_redirect_to_private_or_http_address_is_blocked) ... /usr/lib/python3.14/tempfile.py:484: ResourceWarning: Implicitly cleaning up <HTTPError 403: 'unsafe redirect'>
  _warnings.warn(self.warn_message, ResourceWarning)
ok
test_single_source_worker_can_wait_for_an_http_worker (__main__.ThreadingAndSecurity.test_single_source_worker_can_wait_for_an_http_worker) ... ok
test_token_only_sent_to_github_api_and_not_forwarded (__main__.ThreadingAndSecurity.test_token_only_sent_to_github_api_and_not_forwarded) ... ok
test_credentials_refused (__main__.UrlRules.test_credentials_refused) ... ok
test_https_only (__main__.UrlRules.test_https_only) ... ok
test_private_addresses_refused (__main__.UrlRules.test_private_addresses_refused) ... ok
test_public_ranges_allowed (__main__.UrlRules.test_public_ranges_allowed) ... ok

----------------------------------------------------------------------
Ran 27 tests in 0.025s

OK
```

## nova-patch/nova-tv/tools/site-selftest.log
```
test_concurrent_claims_profile_a_host_once (__main__.PoolTests.test_concurrent_claims_profile_a_host_once) ... ok
test_concurrent_offers_never_exceed_capacity (__main__.PoolTests.test_concurrent_offers_never_exceed_capacity) ... ok
test_merge_cannot_silently_truncate_an_existing_pool (__main__.PoolTests.test_merge_cannot_silently_truncate_an_existing_pool) ... ok
test_merge_carries_recent_attempts_but_expires_old_ones (__main__.PoolTests.test_merge_carries_recent_attempts_but_expires_old_ones) ... ok
test_expired_probe_is_not_cached_as_a_failed_host (__main__.VerificationTests.test_expired_probe_is_not_cached_as_a_failed_host) ... ok
test_hints_without_three_content_links_are_not_verified (__main__.VerificationTests.test_hints_without_three_content_links_are_not_verified) ... ok
test_manifest_seeding_respects_deadline_before_network (__main__.VerificationTests.test_manifest_seeding_respects_deadline_before_network) ... ok
test_offsite_content_links_do_not_make_a_search_pass (__main__.VerificationTests.test_offsite_content_links_do_not_make_a_search_pass) ... ok
test_one_source_worker_can_wait_for_a_profile_worker (__main__.VerificationTests.test_one_source_worker_can_wait_for_a_profile_worker) ... ok
test_profile_worker_bug_propagates_instead_of_false_green (__main__.VerificationTests.test_profile_worker_bug_propagates_instead_of_false_green) ... ok
test_site_is_added_only_after_same_host_title_search (__main__.VerificationTests.test_site_is_added_only_after_same_host_title_search) ... ok

----------------------------------------------------------------------
Ran 11 tests in 0.060s

OK
```

## nova-patch/nova-tv/tools/server-crawl.log
```
round 119  +  0  pool  506/3000  queue  10  requests  3276  refused  910  rate-limited 9  110.0s
round 120  +  0  pool  506/3000  queue   9  requests  3276  refused  910  rate-limited 9  110.3s
round 121  +  0  pool  506/3000  queue   8  requests  3277  refused  910  rate-limited 9  110.4s
round 122  +  0  pool  506/3000  queue   8  requests  3278  refused  910  rate-limited 9  110.5s
round 123  +  0  pool  506/3000  queue   8  requests  3279  refused  945  rate-limited 9  110.6s
round 124  +  0  pool  506/3000  queue   7  requests  3280  refused  945  rate-limited 9  110.6s
round 125  +  0  pool  506/3000  queue   7  requests  3281  refused  985  rate-limited 9  110.7s
round 126  +  0  pool  506/3000  queue   6  requests  3282  refused  985  rate-limited 9  110.7s
round 127  +  0  pool  506/3000  queue   6  requests  3283  refused 1031  rate-limited 9  110.8s
round 128  +  0  pool  506/3000  queue   5  requests  3284  refused 1074  rate-limited 9  110.9s
round 129  +  0  pool  506/3000  queue   5  requests  3284  refused 1113  rate-limited 9  110.9s
round 131  +  0  pool  506/3000  queue   4  requests  3285  refused 1113  rate-limited 9  112.6s
round 132  +  0  pool  506/3000  queue   4  requests  3286  refused 1159  rate-limited 9  112.8s
round 133  +  0  pool  506/3000  queue   4  requests  3287  refused 1194  rate-limited 9  113.0s
round 134  +  0  pool  506/3000  queue   3  requests  3288  refused 1237  rate-limited 9  113.1s
round 135  +  0  pool  506/3000  queue   3  requests  3289  refused 1277  rate-limited 9  113.3s
round 136  +  0  pool  506/3000  queue   2  requests  3290  refused 1320  rate-limited 9  113.4s
round 137  +  0  pool  506/3000  queue   2  requests  3291  refused 1366  rate-limited 9  113.5s
round 138  +  0  pool  506/3000  queue   2  requests  3292  refused 1401  rate-limited 9  113.6s
round 139  +  0  pool  506/3000  queue   1  requests  3293  refused 1444  rate-limited 9  113.8s
round 140  +  0  pool  506/3000  queue   1  requests  3294  refused 1490  rate-limited 9  113.9s
round 141  +  0  pool  506/3000  queue   0  requests  3295  refused 1533  rate-limited 9  114.0s
round 143  +  0  pool  506/3000  queue   1  requests  3296  refused 1533  rate-limited 9  116.0s
round 145  +  0  pool  506/3000  queue   0  requests  3329  refused 1535  rate-limited 9  117.0s
round 146  +  0  pool  506/3000  queue   0  requests  3398  refused 1537  rate-limited 9  117.6s
round 148  +  0  pool  506/3000  queue   0  requests  3399  refused 1537  rate-limited 9  119.2s
round 151  +  0  pool  506/3000  queue   0  requests  3400  refused 1537  rate-limited 9  121.4s
round 155  +  0  pool  506/3000  queue   0  requests  3411  refused 1537  rate-limited 9  124.5s
round 157  +  0  pool  506/3000  queue   0  requests  3412  refused 1537  rate-limited 9  125.8s
round 160  +  0  pool  506/3000  queue   0  requests  3413  refused 1537  rate-limited 9  128.0s
round 163  +  0  pool  506/3000  queue   0  requests  3414  refused 1537  rate-limited 9  130.2s
round 168  +  0  pool  506/3000  queue   0  requests  3485  refused 1541  rate-limited 9  134.6s
round 169  +  0  pool  506/3000  queue   0  requests  3485  refused 1541  rate-limited 9  134.9s
round 171  +  0  pool  506/3000  queue   0  requests  3486  refused 1541  rate-limited 9  136.8s
round 174  +  0  pool  506/3000  queue   0  requests  3487  refused 1541  rate-limited 9  139.0s
round 181  +  0  pool  506/3000  queue   1  requests  3617  refused 1556  rate-limited 9  145.9s
round 186  +  0  pool  506/3000  queue   1  requests  3772  refused 1560  rate-limited 10  150.6s
round 193  +  0  pool  506/3000  queue   1  requests  4048  refused 1566  rate-limited 12  156.8s
round 195  +  0  pool  506/3000  queue   1  requests  4150  refused 1571  rate-limited 12  158.4s
round 196  +  0  pool  506/3000  queue   1  requests  4151  refused 1571  rate-limited 12  158.9s
round 198  +  0  pool  506/3000  queue   0  requests  4260  refused 1578  rate-limited 12  160.6s
round 203  +  0  pool  506/3000  queue   0  requests  4376  refused 1585  rate-limited 12  164.7s
round 204  +  0  pool  506/3000  queue   1  requests  4442  refused 1587  rate-limited 12  165.4s
round 206  +  0  pool  506/3000  queue   1  requests  4465  refused 1587  rate-limited 12  167.1s
round 209  +  0  pool  506/3000  queue   1  requests  4573  refused 1590  rate-limited 12  169.5s
round 212  +  0  pool  506/3000  queue   1  requests  4678  refused 1596  rate-limited 12  171.7s
round 216  +  0  pool  506/3000  queue   0  requests  4877  refused 1617  rate-limited 12  175.1s
round 217  +  0  pool  506/3000  queue   0  requests  4877  refused 1617  rate-limited 12  175.1s
round 220  +  0  pool  506/3000  queue   1  requests  5012  refused 1623  rate-limited 12  177.4s
round 227  +  0  pool  506/3000  queue   1  requests  5287  refused 1644  rate-limited 14  184.3s
round 228  +  0  pool  506/3000  queue   0  requests  5306  refused 1645  rate-limited 14  184.9s
round 230  +  0  pool  506/3000  queue   1  requests  5489  refused 1654  rate-limited 14  186.3s
round 231  +  0  pool  506/3000  queue   0  requests  5490  refused 1655  rate-limited 14  187.3s
round 237  +  0  pool  506/3000  queue   1  requests  5675  refused 1663  rate-limited 15  193.2s
round 238  +  0  pool  506/3000  queue   0  requests  5746  refused 1668  rate-limited 15  193.9s
round 240  +  0  pool  506/3000  queue   0  requests  5779  refused 1669  rate-limited 16  195.8s
round 243  +  0  pool  506/3000  queue   1  requests  5953  refused 1672  rate-limited 17  198.0s
round 245  +  0  pool  506/3000  queue   0  requests  5993  refused 1674  rate-limited 17  199.2s
round 246  +  0  pool  506/3000  queue   1  requests  5993  refused 1674  rate-limited 17  199.3s
round 248  +  0  pool  506/3000  queue   1  requests  5994  refused 1674  rate-limited 17  201.0s
round 253  +  0  pool  506/3000  queue   0  requests  6115  refused 1691  rate-limited 17  205.3s
round 258  +  0  pool  506/3000  queue   1  requests  6325  refused 1700  rate-limited 18  209.5s
round 262  +  0  pool  506/3000  queue   0  requests  6566  refused 1716  rate-limited 19  212.7s
round 263  +  0  pool  506/3000  queue   0  requests  6574  refused 1717  rate-limited 19  212.8s
round 265  +  0  pool  506/3000  queue   0  requests  6672  refused 1723  rate-limited 19  213.8s
round 268  +  0  pool  506/3000  queue   1  requests  6703  refused 1724  rate-limited 19  216.8s
round 272  +  0  pool  506/3000  queue   0  requests  6709  refused 1724  rate-limited 19  220.1s
round 277  +  0  pool  506/3000  queue   0  requests  6932  refused 1744  rate-limited 20  224.3s
round 281  +  0  pool  506/3000  queue   0  requests  6975  refused 1748  rate-limited 20  228.3s
round 295  +  0  pool  506/3000  queue   0  requests  7065  refused 1748  rate-limited 20  241.9s
round 296  +  0  pool  506/3000  queue   0  requests  7065  refused 1748  rate-limited 20  242.0s
round 316  +  0  pool  506/3000  queue   0  requests  7108  refused 1752  rate-limited 20  261.7s
122 rounds with nothing new and nothing queued; stopping

pool      506/3000
file      server_pool.json  (185418 bytes)
requests  7108  failures 4539  refused 1752  rate-limited 20
per source {"github-readme": 1}
elapsed   261.7s
stopped below target: 506 of 3000. The sources above are what produced them; raising --pages/--gh-pages or running again later (GitHub's index changes hourly) will find more.
```

## nova-patch/nova-tv/tools/site-crawl.log
```
merged 18 sites from ../../pools/site_pool.json
seeded 0 site(s) from server_pool.json
round   1  +  0  sites   18/3000  requests  1061  rejected     1  50.5s
          rejected by reason: {'search returned nothing usable': 1}
round   2  +  0  sites   18/3000  requests  2373  rejected     1  106.1s
          rejected by reason: {'search returned nothing usable': 1}
round   3  + 13  sites   31/3000  requests  4947  rejected   491  473.1s
          rejected by reason: {'search returned nothing usable': 282, 'no homepage': 209}
round   4  +  0  sites   31/3000  requests  6003  rejected   491  522.1s
          rejected by reason: {'search returned nothing usable': 282, 'no homepage': 209}
round   5  +  0  sites   31/3000  requests  7315  rejected   491  575.9s
          rejected by reason: {'search returned nothing usable': 282, 'no homepage': 209}
round   6  +  0  sites   31/3000  requests  8387  rejected   491  626.2s
          rejected by reason: {'search returned nothing usable': 282, 'no homepage': 209}
round   7  +  0  sites   31/3000  requests  9443  rejected   491  676.5s
          rejected by reason: {'search returned nothing usable': 282, 'no homepage': 209}
round   8  +  0  sites   31/3000  requests 10593  rejected   491  720.0s
          rejected by reason: {'search returned nothing usable': 282, 'no homepage': 209}
5 rounds with nothing new; stopping

sites     31/3000
file      site_pool.json  (187569 bytes)
rejected  {'search returned nothing usable': 282, 'no homepage': 209}
per source {"github-lists": 2, "github-lists:HACHI-API": 3, "github-lists:La-kika-Streaming-video": 1, "github-lists:Neko-s-Index-Backup-Fall": 2, "github-lists:OtakuPoi": 1, "github-lists:TMDB-Import": 1, "github-lists:Zee5-Stream-Scrapper": 1, "github-lists:projeto-movie-wave-strea": 1, "github-lists:sdl": 1}
stopped below target: 31 of 3000 verified sites.
The count covers this crawl's configured sources and search checks; it does not establish
internet-wide exhaustion or guarantee that a site will produce playable media.
```

