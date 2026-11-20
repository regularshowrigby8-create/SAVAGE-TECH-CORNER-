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


def entry(host):
    return sc.SiteEntry(f"https://{host}", host, [f"https://{host}/search?q={{q}}"])


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

    def test_site_is_added_only_after_same_host_title_search(self):
        pages = {"https://a.example/": HOME, "https://a.example/search?q=one%20piece": GOOD}
        crawler, pool = self.make_crawler(pages)
        found = crawler.profile_and_verify("https://a.example", "fixture")
        self.assertIsNotNone(found)
        self.assertEqual(1, pool.size)
        evidence = crawler.stats.snapshot()["source_evidence"]["fixture"]
        self.assertEqual(1, evidence["candidates"])
        self.assertEqual(1, evidence["added"])

    def test_hints_without_three_content_links_are_not_verified(self):
        page = 'One Piece ' + 'episode 1080p ' * 20 + '<a href="/watch/one-piece">a</a>'
        crawler, pool = self.make_crawler({"https://a.example/": HOME,
                                           "https://a.example/search?q=one%20piece": page})
        self.assertIsNone(crawler.profile_and_verify("https://a.example", "fixture"))
        self.assertEqual(0, pool.size)
        self.assertEqual(1, pool.rejected["search returned nothing usable"])

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
                                           "https://a.example/search?q=one%20piece": GOOD}, workers=1)
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
