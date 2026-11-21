#!/usr/bin/env python3
"""Offline tests for discovery, verification and concurrent site-pool imports."""
import json
import tempfile
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import site_crawler as sc


HOME = '<title>Example</title><form action="/search" method="get"><input name="q" type="text"></form>'
GOOD = ('One Piece episode episode episode 1080p 720p '
        '<a href="/watch/one-piece-1">a</a><a href="/watch/one-piece-2">b</a>'
        '<a href="/episode/one-piece-3">c</a>')
PLAYER = '<iframe src="https://player.example/embed/123"></iframe>'
CONTROL_URL = f"https://a.example/search?q={sc.CONTROL_QUERY}"


def entry(host):
    return sc.SiteEntry(f"https://{host}", host, [f"https://{host}/search?q={{q}}"],
                        verification={"method": sc.VERIFICATION_METHOD, "probe": "one piece",
                                      "title_path": "/movie/one-piece", "control": "different-results",
                                      "player_marker": "iframe"})


class PoolTests(unittest.TestCase):
    def test_concurrent_offers_never_exceed_capacity(self):
        pool = sc.SitePool(target=3)
        with ThreadPoolExecutor(max_workers=20) as workers:
            outcomes = list(workers.map(lambda n: pool.offer(entry(f"s{n}.example")), range(100)))
        self.assertEqual(3, sum(outcomes))
        self.assertEqual(3, pool.size)

    def test_concurrent_claims_profile_a_host_once(self):
        pool = sc.SitePool()
        with ThreadPoolExecutor(max_workers=20) as workers:
            outcomes = list(workers.map(lambda _: pool.start_host("same.example"), range(100)))
        self.assertEqual(1, sum(outcomes))

    def test_merge_carries_recent_attempts_but_expires_old_ones(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "pool.json"
            now = time.time()
            path.write_text(json.dumps({"entries": [entry("a.example").as_json()],
                                        "attempted": {"fresh.example": now, "old.example": now - 90000}}))
            pool = sc.SitePool()
            self.assertEqual(1, pool.merge_file(path))
            self.assertFalse(pool.start_host("fresh.example"))
            self.assertTrue(pool.start_host("old.example"))
            self.assertEqual(1, len(pool.as_document(pool.rejected)["entries"]))

    def test_legacy_entries_are_queued_not_counted_and_survive_reloads(self):
        with tempfile.TemporaryDirectory() as tmp:
            old = Path(tmp) / "old.json"
            legacy = entry("old.example").as_json()
            del legacy["verification"]
            old.write_text(json.dumps({"entries": [legacy], "attempted": {"old.example": time.time()}}))
            pool = sc.SitePool()
            self.assertEqual(0, pool.merge_file(old))
            self.assertEqual(["https://old.example"], pool.pending_origins())
            self.assertTrue(pool.start_host("old.example"), "the first legacy recheck must not be skipped")
            new = Path(tmp) / "new.json"
            new.write_text(json.dumps(pool.as_document(pool.rejected)))
            restored = sc.SitePool()
            self.assertEqual(0, restored.merge_file(new))
            self.assertEqual(["https://old.example"], restored.pending_origins())
            self.assertFalse(restored.start_host("old.example"), "failed rechecks retain their cooldown")
            self.assertTrue(restored.offer(entry("old.example")))
            self.assertEqual([], restored.pending_origins())

    def test_reintroducing_legacy_does_not_override_a_checked_host(self):
        with tempfile.TemporaryDirectory() as tmp:
            prior = Path(tmp) / "prior.json"
            legacy = entry("checked.example").as_json()
            del legacy["verification"]
            prior.write_text(json.dumps({"entries": [legacy]}))
            pool = sc.SitePool()
            self.assertTrue(pool.offer(entry("checked.example")))
            self.assertEqual(0, pool.merge_file(prior))
            self.assertEqual([], pool.pending_origins())
            self.assertEqual(1, pool.size)

    def test_merge_cannot_silently_truncate_an_existing_pool(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "pool.json"
            path.write_text(json.dumps({"entries": [entry("a.example").as_json(), entry("b.example").as_json()]}))
            with self.assertRaises(ValueError):
                sc.SitePool(target=1).merge_file(path)


class VerificationTests(unittest.TestCase):
    def make_crawler(self, pages, workers=2):
        pool = sc.SitePool(target=10)
        crawler = sc.SiteCrawler(pool, sc.Stats(), workers=workers)
        crawler.get = lambda url, accept="": pages.get(url)
        self.addCleanup(crawler.shutdown)
        return crawler, pool

    def test_site_is_added_only_after_same_host_title_search_and_player_marker(self):
        pages = {"https://a.example/": HOME, "https://a.example/search?q=one%20piece": GOOD,
                 CONTROL_URL: "<h1>No results</h1>", "https://a.example/watch/one-piece-1": PLAYER}
        crawler, pool = self.make_crawler(pages)
        found = crawler.profile_and_verify("https://a.example", "fixture")
        self.assertIsNotNone(found)
        self.assertEqual(1, pool.size)
        self.assertEqual(["https://a.example/search?q={q}"], found.templates)
        self.assertEqual(sc.VERIFICATION_METHOD, found.verification["method"])
        self.assertEqual("iframe", found.verification["player_marker"])
        evidence = crawler.stats.snapshot()["source_evidence"]["fixture"]
        self.assertEqual(1, evidence["candidates"])
        self.assertEqual(1, evidence["added"])

    def test_title_search_without_a_player_is_not_a_streaming_candidate(self):
        page = 'One Piece ' + 'episode 1080p ' * 20 + '<a href="/watch/one-piece">a</a>'
        crawler, pool = self.make_crawler({"https://a.example/": HOME,
                                           "https://a.example/search?q=one%20piece": page,
                                           CONTROL_URL: "<h1>No results</h1>",
                                           "https://a.example/watch/one-piece": '<h1>One Piece</h1>'})
        self.assertIsNone(crawler.profile_and_verify("https://a.example", "fixture"))
        self.assertEqual(0, pool.size)
        self.assertEqual(1, pool.rejected["no player marker on sampled title"])

    def test_reflected_query_with_unrelated_content_links_is_not_a_match(self):
        page = ('<input value="one piece">' +
                '<a href="/movie/unrelated">one</a><a href="/movie/other">two</a>' +
                '<a href="/episode/another">three</a>')
        self.assertEqual((0, 0), sc.result_score(page, "one piece", "https://a.example"))
        crawler, pool = self.make_crawler({"https://a.example/": HOME,
                                           "https://a.example/search?q=one%20piece": page})
        self.assertIsNone(crawler.profile_and_verify("https://a.example", "fixture"))
        self.assertEqual(0, pool.size)

    def test_homepage_that_echoes_query_and_always_shows_same_title_is_rejected(self):
        page = ('<input value="one piece">'
                '<a href="/watch/one-piece-1?utm_source=search">One Piece</a>')
        control = '<a href="/watch/one-piece-1?utm_source=home">One Piece</a>'
        pages = {"https://a.example/": HOME, "https://a.example/search?q=one%20piece": page,
                 CONTROL_URL: control, "https://a.example/watch/one-piece-1?utm_source=search": PLAYER}
        crawler, pool = self.make_crawler(pages)
        self.assertIsNone(crawler.profile_and_verify("https://a.example", "fixture"))
        self.assertEqual(0, pool.size)
        self.assertEqual(1, pool.rejected["search control unavailable or unchanged"])

    def test_a_failed_control_is_not_treated_as_proof_of_search(self):
        pages = {"https://a.example/": HOME, "https://a.example/search?q=one%20piece": GOOD,
                 "https://a.example/watch/one-piece-1": PLAYER}
        crawler, pool = self.make_crawler(pages)
        self.assertIsNone(crawler.profile_and_verify("https://a.example", "fixture"))
        self.assertEqual(1, pool.rejected["search control unavailable or unchanged"])

    def test_matching_result_must_be_parseable_by_the_app(self):
        page = ('<a href="/home/movie/one-piece">One Piece</a>'
                '<a href="/search?q=one-piece">One Piece</a>'
                "<a href='/movie/one-piece'>One Piece</a>"  # app only parses double quotes
                '<a href="https://other.example/movie/one-piece">One Piece</a>')
        self.assertEqual([], sc.matched_title_links(page, "one piece", "https://a.example"))
        valid = '<a href="/movie/42"><img alt="One Piece" src="/x.jpg"></a>'
        self.assertEqual(["https://a.example/movie/42"],
                         sc.matched_title_links(valid, "one piece", "https://a.example"))

    def test_common_trailer_and_ad_frames_are_not_players(self):
        self.assertEqual("", sc.player_marker('<iframe src="https://www.youtube.com/embed/abc"></iframe>',
                                              "https://a.example/movie/one-piece"))
        self.assertEqual("", sc.player_marker('<iframe src="https://doubleclick.net/ads/foo"></iframe>',
                                              "https://a.example/movie/one-piece"))
        self.assertEqual("direct-media", sc.player_marker('<video src="/media/film.mp4"></video>',
                                                          "https://a.example/movie/one-piece"))
        self.assertEqual("iframe", sc.player_marker(PLAYER, "https://a.example/movie/one-piece"))

    def test_player_may_be_found_on_a_same_host_episode_page(self):
        title_page = '<a href="/anime/one-piece/episode-1">Episode 1</a>'
        pages = {"https://a.example/": HOME, "https://a.example/search?q=one%20piece": GOOD,
                 CONTROL_URL: "<h1>No results</h1>", "https://a.example/watch/one-piece-1": title_page,
                 "https://a.example/anime/one-piece/episode-1": PLAYER}
        crawler, pool = self.make_crawler(pages)
        found = crawler.profile_and_verify("https://a.example", "fixture")
        self.assertIsNotNone(found)
        self.assertEqual(1, pool.size)

    def test_offsite_content_links_do_not_make_a_search_pass(self):
        page = GOOD.replace('href="/', 'href="https://unrelated.example/')
        crawler, pool = self.make_crawler({"https://a.example/": HOME,
                                           "https://a.example/search?q=one%20piece": page})
        self.assertIsNone(crawler.profile_and_verify("https://a.example", "fixture"))
        self.assertEqual(0, pool.size)

    def test_expired_probe_is_not_cached_as_a_failed_host(self):
        crawler, pool = self.make_crawler({})
        def timed_out(url, accept=""):
            crawler.deadline = time.time() - 1
            return None
        crawler.get = timed_out
        self.assertIsNone(crawler.profile_and_verify("https://a.example", "fixture"))
        self.assertNotIn("a.example", pool.attempted)
        self.assertEqual(0, sum(pool.rejected.values()))

    def test_one_source_worker_can_wait_for_a_profile_worker(self):
        crawler, pool = self.make_crawler({"https://a.example/": HOME,
                                           "https://a.example/search?q=one%20piece": GOOD,
                                           CONTROL_URL: "<h1>No results</h1>",
                                           "https://a.example/watch/one-piece-1": PLAYER}, workers=1)
        future = crawler.submit_source(crawler._profile_all, ["https://a.example"], "fixture")
        self.assertEqual(1, future.result(timeout=3))
        self.assertEqual(1, pool.size)

    def test_profile_worker_bug_propagates_instead_of_false_green(self):
        crawler, _ = self.make_crawler({})
        def broken(*args):
            raise RuntimeError("planted worker failure")
        crawler.profile_and_verify = broken
        with self.assertRaisesRegex(RuntimeError, "site profiling failed"):
            crawler._profile_all(["https://a.example"], "fixture")

    def test_manifest_seeding_respects_deadline_before_network(self):
        crawler, pool = self.make_crawler({})
        crawler.deadline = time.time() - 1
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "manifest.json"
            path.write_text('{"entries": [{"baseUrl": "https://a.example"}]}')
            self.assertEqual(0, crawler.from_manifest_pool(path))
            self.assertEqual(0, pool.size)
            self.assertEqual(0, crawler.stats.snapshot()["requests"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
