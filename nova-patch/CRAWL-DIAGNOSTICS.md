# Nova crawl failure diagnostics

Run: 44886402950

## crawl-results/server-selftest.log
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
Ran 27 tests in 0.018s

OK
```

## crawl-results/site-selftest.log
```
test_concurrent_claims_profile_a_host_once (__main__.PoolTests.test_concurrent_claims_profile_a_host_once) ... ok
test_concurrent_offers_never_exceed_capacity (__main__.PoolTests.test_concurrent_offers_never_exceed_capacity) ... ok
test_legacy_entries_are_queued_not_counted_and_survive_reloads (__main__.PoolTests.test_legacy_entries_are_queued_not_counted_and_survive_reloads) ... ok
test_merge_cannot_silently_truncate_an_existing_pool (__main__.PoolTests.test_merge_cannot_silently_truncate_an_existing_pool) ... ok
test_merge_carries_recent_attempts_but_expires_old_ones (__main__.PoolTests.test_merge_carries_recent_attempts_but_expires_old_ones) ... ok
test_reintroducing_legacy_does_not_override_a_checked_host (__main__.PoolTests.test_reintroducing_legacy_does_not_override_a_checked_host) ... ok
test_a_failed_control_is_not_treated_as_proof_of_search (__main__.VerificationTests.test_a_failed_control_is_not_treated_as_proof_of_search) ... ok
test_common_trailer_and_ad_frames_are_not_players (__main__.VerificationTests.test_common_trailer_and_ad_frames_are_not_players) ... ok
test_expired_probe_is_not_cached_as_a_failed_host (__main__.VerificationTests.test_expired_probe_is_not_cached_as_a_failed_host) ... ok
test_homepage_that_echoes_query_and_always_shows_same_title_is_rejected (__main__.VerificationTests.test_homepage_that_echoes_query_and_always_shows_same_title_is_rejected) ... ok
test_manifest_seeding_respects_deadline_before_network (__main__.VerificationTests.test_manifest_seeding_respects_deadline_before_network) ... ok
test_matching_result_must_be_parseable_by_the_app (__main__.VerificationTests.test_matching_result_must_be_parseable_by_the_app) ... ok
test_offsite_content_links_do_not_make_a_search_pass (__main__.VerificationTests.test_offsite_content_links_do_not_make_a_search_pass) ... ok
test_one_source_worker_can_wait_for_a_profile_worker (__main__.VerificationTests.test_one_source_worker_can_wait_for_a_profile_worker) ... ok
test_player_may_be_found_on_a_same_host_episode_page (__main__.VerificationTests.test_player_may_be_found_on_a_same_host_episode_page) ... ok
test_profile_worker_bug_propagates_instead_of_false_green (__main__.VerificationTests.test_profile_worker_bug_propagates_instead_of_false_green) ... ok
test_reflected_query_with_unrelated_content_links_is_not_a_match (__main__.VerificationTests.test_reflected_query_with_unrelated_content_links_is_not_a_match) ... ok
test_site_is_added_only_after_same_host_title_search_and_player_marker (__main__.VerificationTests.test_site_is_added_only_after_same_host_title_search_and_player_marker) ... ok
test_title_search_without_a_player_is_not_a_streaming_candidate (__main__.VerificationTests.test_title_search_without_a_player_is_not_a_streaming_candidate) ... ok

----------------------------------------------------------------------
Ran 19 tests in 0.048s

OK
```

## crawl-results/server-crawl.log
```
round  75  +  0  pool  517/3000  queue  30  requests  2390  refused  595  rate-limited 5  64.1s
round  77  +  0  pool  517/3000  queue  29  requests  2482  refused  605  rate-limited 5  65.4s
round  78  +  0  pool  517/3000  queue  28  requests  2485  refused  606  rate-limited 5  66.3s
round  81  +  0  pool  517/3000  queue  27  requests  2486  refused  606  rate-limited 5  68.5s
round  88  +  0  pool  517/3000  queue  27  requests  2629  refused  629  rate-limited 5  74.8s
round  89  +  0  pool  517/3000  queue  26  requests  2629  refused  629  rate-limited 5  74.8s
round  90  +  0  pool  517/3000  queue  26  requests  2632  refused  629  rate-limited 5  78.8s
round  91  +  0  pool  517/3000  queue  25  requests  2632  refused  629  rate-limited 5  78.8s
round  92  +  1  pool  518/3000  queue  24  requests  2814  refused  679  rate-limited 5  82.9s
round  93  +  0  pool  518/3000  queue  25  requests  2817  refused  720  rate-limited 5  86.9s
round  94  +  0  pool  518/3000  queue  22  requests  2817  refused  720  rate-limited 5  86.9s
round  95  +  0  pool  518/3000  queue  19  requests  2822  refused  755  rate-limited 5  86.9s
round  96  +  0  pool  518/3000  queue  18  requests  2822  refused  798  rate-limited 5  86.9s
round  97  +  0  pool  518/3000  queue  18  requests  2822  refused  843  rate-limited 5  87.0s
round  98  +  0  pool  518/3000  queue  18  requests  2822  refused  886  rate-limited 5  87.0s
round  99  +  0  pool  518/3000  queue  18  requests  2847  refused  887  rate-limited 5  91.0s
round 100  +  0  pool  518/3000  queue  17  requests  2847  refused  887  rate-limited 5  91.0s
round 102  +  0  pool  518/3000  queue  15  requests  2848  refused  887  rate-limited 5  92.7s
round 105  +  0  pool  518/3000  queue  14  requests  2849  refused  887  rate-limited 5  95.0s
round 114  +  0  pool  518/3000  queue  14  requests  3082  refused  905  rate-limited 5  103.5s
round 115  +  0  pool  518/3000  queue  14  requests  3082  refused  906  rate-limited 5  103.6s
round 118  +  0  pool  518/3000  queue  13  requests  3189  refused  919  rate-limited 6  105.9s
round 121  +  0  pool  518/3000  queue  12  requests  3296  refused  922  rate-limited 7  108.1s
round 122  +  0  pool  518/3000  queue  12  requests  3296  refused  922  rate-limited 7  108.8s
round 123  +  0  pool  518/3000  queue  11  requests  3297  refused  922  rate-limited 7  108.8s
round 124  +  0  pool  518/3000  queue  10  requests  3298  refused  922  rate-limited 7  108.9s
round 125  +  0  pool  518/3000  queue   9  requests  3299  refused  922  rate-limited 7  108.9s
round 126  +  0  pool  518/3000  queue   9  requests  3300  refused  957  rate-limited 7  109.0s
round 127  +  0  pool  518/3000  queue   9  requests  3301  refused  998  rate-limited 7  109.1s
round 128  +  0  pool  518/3000  queue   9  requests  3302  refused 1043  rate-limited 7  109.1s
round 129  +  0  pool  518/3000  queue   8  requests  3303  refused 1086  rate-limited 7  109.2s
round 130  +  0  pool  518/3000  queue   8  requests  3304  refused 1125  rate-limited 7  109.2s
round 131  +  0  pool  518/3000  queue   7  requests  3305  refused 1125  rate-limited 7  109.3s
round 133  +  0  pool  518/3000  queue   6  requests  3306  refused 1125  rate-limited 7  110.3s
round 134  +  0  pool  518/3000  queue   5  requests  3306  refused 1125  rate-limited 7  111.3s
round 136  +  0  pool  518/3000  queue   4  requests  3307  refused 1125  rate-limited 7  112.5s
round 137  +  0  pool  518/3000  queue   4  requests  3308  refused 1170  rate-limited 7  112.5s
round 138  +  0  pool  518/3000  queue   4  requests  3309  refused 1205  rate-limited 7  112.6s
round 139  +  0  pool  518/3000  queue   3  requests  3310  refused 1248  rate-limited 7  112.7s
round 140  +  0  pool  518/3000  queue   3  requests  3311  refused 1289  rate-limited 7  112.7s
round 141  +  0  pool  518/3000  queue   2  requests  3312  refused 1332  rate-limited 7  112.8s
round 142  +  0  pool  518/3000  queue   2  requests  3313  refused 1377  rate-limited 7  112.8s
round 143  +  0  pool  518/3000  queue   2  requests  3314  refused 1412  rate-limited 7  112.9s
round 144  +  0  pool  518/3000  queue   1  requests  3315  refused 1455  rate-limited 7  112.9s
round 145  +  0  pool  518/3000  queue   1  requests  3316  refused 1500  rate-limited 7  113.0s
round 146  +  0  pool  518/3000  queue   0  requests  3317  refused 1543  rate-limited 7  113.0s
round 150  +  0  pool  518/3000  queue   0  requests  3351  refused 1545  rate-limited 7  116.9s
round 151  +  0  pool  518/3000  queue   0  requests  3420  refused 1547  rate-limited 7  117.4s
round 153  +  0  pool  518/3000  queue   0  requests  3421  refused 1547  rate-limited 7  119.1s
round 156  +  0  pool  518/3000  queue   0  requests  3422  refused 1547  rate-limited 7  121.3s
round 159  +  0  pool  518/3000  queue   0  requests  3433  refused 1547  rate-limited 7  123.9s
round 161  +  0  pool  518/3000  queue   0  requests  3434  refused 1547  rate-limited 7  125.7s
round 164  +  0  pool  518/3000  queue   0  requests  3435  refused 1547  rate-limited 7  127.9s
round 167  +  0  pool  518/3000  queue   0  requests  3436  refused 1547  rate-limited 7  130.1s
round 172  +  0  pool  518/3000  queue   0  requests  3507  refused 1551  rate-limited 7  134.5s
round 173  +  0  pool  518/3000  queue   0  requests  3507  refused 1551  rate-limited 7  135.0s
round 175  +  0  pool  518/3000  queue   0  requests  3508  refused 1551  rate-limited 7  136.7s
round 178  +  0  pool  518/3000  queue   0  requests  3509  refused 1551  rate-limited 7  138.9s
round 185  +  0  pool  518/3000  queue   1  requests  3640  refused 1566  rate-limited 7  145.3s
round 190  +  0  pool  518/3000  queue   1  requests  3855  refused 1572  rate-limited 7  149.4s
round 195  +  0  pool  518/3000  queue   1  requests  4007  refused 1577  rate-limited 9  153.8s
round 200  +  0  pool  518/3000  queue   1  requests  4176  refused 1581  rate-limited 10  158.1s
round 203  +  0  pool  518/3000  queue   1  requests  4210  refused 1582  rate-limited 10  160.2s
round 205  +  0  pool  518/3000  queue   1  requests  4249  refused 1584  rate-limited 10  161.6s
round 206  +  0  pool  518/3000  queue   0  requests  4317  refused 1588  rate-limited 10  162.1s
round 208  +  0  pool  518/3000  queue   1  requests  4462  refused 1591  rate-limited 10  163.9s
round 209  +  0  pool  518/3000  queue   0  requests  4462  refused 1593  rate-limited 10  164.0s
round 214  +  0  pool  518/3000  queue   1  requests  4596  refused 1599  rate-limited 10  168.8s
round 217  +  0  pool  518/3000  queue   1  requests  4712  refused 1623  rate-limited 10  171.3s
round 221  +  0  pool  518/3000  queue   0  requests  4900  refused 1633  rate-limited 10  175.1s
round 222  +  0  pool  518/3000  queue   1  requests  4963  refused 1636  rate-limited 10  175.4s
round 225  +  0  pool  518/3000  queue   2  requests  5050  refused 1640  rate-limited 10  182.2s
time limit reached

pool      518/3000
file      /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/crawl-results/server_pool.json  (189868 bytes)
requests  5050  failures 3128  refused 1640  rate-limited 10
per source {"github-pages": 2, "github-readme": 7, "homepage": 3}
elapsed   187.5s
stopped below target: 518 of 3000. The sources above are what produced them; raising --pages/--gh-pages or running again later (GitHub's index changes hourly) will find more.
```

## crawl-results/site-crawl.log
```
merged 0 sites from ../../pools/site_pool.json
rechecked 31 legacy candidate(s); 1 passed the current heuristic
seeded 0 site(s) from /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/crawl-results/server_pool.json
round   1  +  0  sites    1/3000  requests  1487  rejected    40  76.5s
          rejected by reason: {'search returned nothing usable': 23, 'no player marker on sampled title': 7, 'no homepage': 6, 'search control unavailable or unchanged': 4}
round   2  +  0  sites    1/3000  requests  2565  rejected    42  124.5s
          rejected by reason: {'search returned nothing usable': 24, 'no homepage': 7, 'no player marker on sampled title': 7, 'search control unavailable or unchanged': 4}
round   3  +  0  sites    1/3000  requests  3628  rejected    45  175.5s
          rejected by reason: {'search returned nothing usable': 25, 'no homepage': 9, 'no player marker on sampled title': 7, 'search control unavailable or unchanged': 4}
round   4  +  0  sites    1/3000  requests  3639  rejected    45  178.8s
          rejected by reason: {'search returned nothing usable': 25, 'no homepage': 9, 'no player marker on sampled title': 7, 'search control unavailable or unchanged': 4}
time limit reached

sites     1/3000
file      /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/crawl-results/site_pool.json  (171202 bytes)
rejected  {'search returned nothing usable': 25, 'no homepage': 9, 'no player marker on sampled title': 7, 'search control unavailable or unchanged': 4}
per source {"legacy-recheck": 1}
stopped below target: 1 of 3000 site candidates passed the current heuristic.
The count covers this crawl's configured sources and search checks; it does not establish
internet-wide exhaustion or guarantee that a site will produce playable media.
```

## crawl-results/source-fetch.log
```
From https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-
 * [new branch]      arena/nova-final-debug -> origin/nova-src
```

## crawl-results/apply-tests.log
```
test_check_is_read_only (__main__.ApplyPatchTests.test_check_is_read_only) ... ok
test_one_drifted_file_prevents_all_writes (__main__.ApplyPatchTests.test_one_drifted_file_prevents_all_writes) ... ok
test_unrecorded_patch_path_is_a_conflict (__main__.ApplyPatchTests.test_unrecorded_patch_path_is_a_conflict) ... ok
test_write_preserves_root_readme_and_creates_backup (__main__.ApplyPatchTests.test_write_preserves_root_readme_and_creates_backup) ... ok

----------------------------------------------------------------------
Ran 4 tests in 0.006s

OK
```

## crawl-results/apply.log
```
patch tree : /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-patch/nova-tv
base commit: 334719831cffc93f1d8b635cf8235d01ed7adacf
target     : /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work
files      : 22  to change 22  already applied 0  conflicts 0

  + nova-tv/android/app/src/main/assets/server_pool.json  (new file, 189868 B)
  + nova-tv/android/app/src/main/assets/site_pool.json  (new file, 171202 B)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/NovaApplication.kt  (87 changed lines)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/data/repo/LibraryStore.kt  (16 changed lines)
  + nova-tv/android/app/src/main/java/dev/nova/tv/data/store/AtomicFileStore.kt  (new file, 1551 B)
  + nova-tv/android/app/src/main/java/dev/nova/tv/finder/FillCoordinator.kt  (new file, 2400 B)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/finder/SourceEngine.kt  (161 changed lines)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/finder/pool/ServerHarvester.kt  (768 changed lines)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/finder/pool/ServerPool.kt  (99 changed lines)
  + nova-tv/android/app/src/main/java/dev/nova/tv/finder/website/SitePool.kt  (new file, 10157 B)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaSources.kt  (94 changed lines)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaViewModel.kt  (90 changed lines)
  ~ nova-tv/android/app/src/mobile/java/dev/nova/tv/mobile/MobileSources.kt  (46 changed lines)
  + nova-tv/android/app/src/test/java/dev/nova/tv/data/store/AtomicFileStoreTest.kt  (new file, 1111 B)
  + nova-tv/android/app/src/test/java/dev/nova/tv/finder/FillCoordinatorTest.kt  (new file, 4020 B)
  + nova-tv/android/app/src/test/java/dev/nova/tv/finder/pool/ServerPoolBundledTest.kt  (new file, 2615 B)
  + nova-tv/android/app/src/test/java/dev/nova/tv/finder/pool/ServerPoolFillTest.kt  (new file, 8312 B)
  + nova-tv/android/app/src/test/java/dev/nova/tv/finder/website/SitePoolTest.kt  (new file, 11636 B)
  + nova-tv/tools/server_crawler.py  (new file, 40991 B)
  + nova-tv/tools/site_crawler.py  (new file, 45087 B)
  + nova-tv/tools/test_server_crawler.py  (new file, 14758 B)
  + nova-tv/tools/test_site_crawler.py  (new file, 12771 B)

sha256 of the files to write (first 12): 884385862c66

wrote 22 file(s); previous versions are under /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/.nova-patch-backup/
next: build and test the patched checkout before distributing it
```

## crawl-results/apk-verification.log
```
server_pool.json: 518 entries in both APKs
site_pool.json: 1 entries in both APKs
tv: app-tv-debug.apk sha256=3364b757571f0a9acc28e180c5d7869a5c30c0fb49b37ef6abd753bd8ab34cc3
mobile: app-mobile-debug.apk sha256=71747b204af29d94d26d4c14357dbf579758a33eca1c82828b68a79e030e5d96
Packaging and recorded heuristic metadata checked; playback, licensing and liveness were not verified.
```

## work/nova-tv/android/crawl-build.log
```
w: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/main/java/dev/nova/tv/player/engine/exoplayer/SurroundAudioRecovery.kt:59:24 'static fun isDirectPlaybackSupported(p0: AudioFormat, p1: AudioAttributes): Boolean' is deprecated. Deprecated in Java.

> Task :app:compileMobileDebugKotlin
w: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/main/java/dev/nova/tv/player/engine/exoplayer/NovaRenderersFactory.kt:80:14 'fun setEnableAudioTrackPlaybackParams(p0: Boolean): DefaultAudioSink.Builder' is deprecated. Deprecated in Java.
w: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/main/java/dev/nova/tv/player/engine/exoplayer/SurroundAudioRecovery.kt:59:24 'static fun isDirectPlaybackSupported(p0: AudioFormat, p1: AudioAttributes): Boolean' is deprecated. Deprecated in Java.

> Task :app:compileTvDebugJavaWithJavac
> Task :app:bundleTvDebugClassesToRuntimeJar
> Task :app:bundleTvDebugClassesToCompileJar
> Task :app:processTvDebugJavaRes
> Task :app:javaPreCompileMobileDebug FROM-CACHE
> Task :app:compileMobileDebugJavaWithJavac
> Task :app:bundleMobileDebugClassesToRuntimeJar
> Task :app:bundleMobileDebugClassesToCompileJar
> Task :app:compileTvDebugUnitTestKotlin
> Task :app:compileTvDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processTvDebugUnitTestJavaRes
> Task :app:compileMobileDebugUnitTestKotlin
> Task :app:testTvDebugUnitTest
> Task :app:preMobileDebugUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileMobileDebugUnitTest FROM-CACHE
> Task :app:compileMobileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processMobileDebugJavaRes
> Task :app:processMobileDebugUnitTestJavaRes
> Task :app:testMobileDebugUnitTest
> Task :app:mergeTvDebugNativeDebugMetadata NO-SOURCE
> Task :app:mergeTvDebugShaders
> Task :app:compileTvDebugShaders NO-SOURCE
> Task :app:generateTvDebugAssets UP-TO-DATE
> Task :app:mergeTvDebugAssets
> Task :app:compressTvDebugAssets
> Task :app:desugarTvDebugFileDependencies
> Task :app:checkTvDebugDuplicateClasses
> Task :app:dexBuilderTvDebug
> Task :app:mergeLibDexTvDebug
> Task :app:mergeProjectDexTvDebug
> Task :app:mergeExtDexTvDebug
> Task :app:mergeTvDebugGlobalSynthetics
> Task :app:mergeTvDebugJavaResource
> Task :app:mergeTvDebugJniLibFolders
> Task :app:mergeTvDebugNativeLibs

> Task :app:stripTvDebugDebugSymbols
Unable to strip the following libraries, packaging them as they are: libandroidx.graphics.path.so, libffmpegJNI.so. Run with --info option to learn more.

> Task :app:validateSigningTvDebug
> Task :app:writeTvDebugAppMetadata
> Task :app:writeTvDebugSigningConfigVersions
> Task :app:mergeMobileDebugNativeDebugMetadata NO-SOURCE
> Task :app:mergeMobileDebugShaders
> Task :app:compileMobileDebugShaders NO-SOURCE
> Task :app:generateMobileDebugAssets UP-TO-DATE
> Task :app:mergeMobileDebugAssets
> Task :app:compressMobileDebugAssets FROM-CACHE
> Task :app:checkMobileDebugDuplicateClasses
> Task :app:desugarMobileDebugFileDependencies FROM-CACHE
> Task :app:mergeExtDexMobileDebug FROM-CACHE
> Task :app:packageTvDebug
> Task :app:createTvDebugApkListingFileRedirect
> Task :app:assembleTvDebug
> Task :app:mergeLibDexMobileDebug FROM-CACHE
> Task :app:dexBuilderMobileDebug
> Task :app:mergeMobileDebugGlobalSynthetics FROM-CACHE
> Task :app:mergeMobileDebugJavaResource
> Task :app:mergeMobileDebugJniLibFolders
> Task :app:mergeMobileDebugNativeLibs

> Task :app:stripMobileDebugDebugSymbols
Unable to strip the following libraries, packaging them as they are: libandroidx.graphics.path.so, libffmpegJNI.so. Run with --info option to learn more.

> Task :app:validateSigningMobileDebug
> Task :app:writeMobileDebugAppMetadata
> Task :app:writeMobileDebugSigningConfigVersions
> Task :app:mergeProjectDexMobileDebug
> Task :app:packageMobileDebug
> Task :app:createMobileDebugApkListingFileRedirect
> Task :app:assembleMobileDebug

BUILD SUCCESSFUL in 2m 36s
90 actionable tasks: 80 executed, 10 from cache
```

