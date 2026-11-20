#!/usr/bin/env python3
"""Offline tests for tools/server_crawler.py.

The crawler talks to the open internet, but its *rules* must not need it: what
counts as a server, what gets refused, how the pool caps and dedupes, and how the
loop decides to stop are all pure logic. This test replaces the fetcher with a
fixture map, so it runs in CI with no network and cannot pass by accident on a
live site that happens to be up.

Run: python3 tools/test_server_crawler.py     (exit 0 = all pass)
"""
from __future__ import annotations

import json
import sys
import time
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import server_crawler as sc  # noqa: E402


STREAM_MANIFEST = {
    "id": "org.example.addon",
    "name": "Example Streams",
    "types": ["movie", "series"],
    "resources": ["catalog", {"name": "stream", "types": ["movie"]}],
    "idPrefixes": ["tt"],
}
CATALOG_ONLY_MANIFEST = {"id": "x", "name": "Catalog", "types": ["movie"], "resources": ["catalog"]}


class FakeFetcher:
    """Same interface as Fetcher, answers from a dict, counts what was asked."""

    def __init__(self, pages: dict, stats: sc.Stats) -> None:
        self.pages = pages
        self.stats = stats
        self.asked: list[str] = []
        self.github_token = ""
        self.shut_down = False

    def get(self, url, headers=None):
        self.asked.append(url)
        self.stats.bump("requests")
        if not sc.is_public_https(url):
            self.stats.bump("refused")
            return None
        return self.pages.get(url)

    def submit(self, fn, *args):
        from concurrent.futures import Future
        future: Future = Future()
        try:
            future.set_result(fn(*args))
        except BaseException as error:      # mirror a worker: the error surfaces on result()
            future.set_exception(error)
        return future

    def submit_source(self, fn, *args):
        return self.submit(fn, *args)

    def shutdown(self) -> None:
        """Nothing to tear down: there is no thread pool in the fake."""
        self.shut_down = True


def crawler_with(pages: dict, target: int = 10) -> tuple[sc.Crawler, sc.Pool, sc.Stats]:
    stats = sc.Stats()
    pool = sc.Pool(target=target)
    return sc.Crawler(pool, FakeFetcher(pages, stats)), pool, stats


class UrlRules(unittest.TestCase):
    def test_https_only(self):
        self.assertTrue(sc.is_public_https("https://a.example/manifest.json"))
        self.assertFalse(sc.is_public_https("http://a.example/manifest.json"))

    def test_private_addresses_refused(self):
        for url in ("https://127.0.0.1/m", "https://10.0.0.5/m", "https://192.168.0.1/m",
                    "https://172.16.3.4/m", "https://172.31.9.9/m", "https://100.64.1.1/m",
                    "https://169.254.169.254/m", "https://localhost/m", "https://x.local/m",
                    "https://[fd00::1]/m"):
            self.assertFalse(sc.is_public_https(url), url)

    def test_public_ranges_allowed(self):
        for url in ("https://8.8.8.8/m", "https://172.32.0.1/m", "https://100.63.0.1/m"):
            self.assertTrue(sc.is_public_https(url), url)

    def test_credentials_refused(self):
        self.assertFalse(sc.is_public_https("https://u:p@a.example/m"))


class ManifestRules(unittest.TestCase):
    def test_stream_resource_required(self):
        self.assertTrue(sc.manifest_ok(STREAM_MANIFEST))
        self.assertFalse(sc.manifest_ok(CATALOG_ONLY_MANIFEST))
        self.assertFalse(sc.manifest_ok({}))
        self.assertFalse(sc.manifest_ok({"resources": ["stream"], "types": ["channel"]}))

    def test_malformed_public_manifest_is_refused_not_a_crash(self):
        for manifest in ({"resources": [3], "types": [None]},
                         {"resources": "stream", "types": ["movie"]},
                         {"resources": ["stream"], "types": [3, None]}):
            self.assertFalse(sc.manifest_ok(manifest), manifest)

    def test_keys_match_the_app(self):
        self.assertEqual(sc.pool_key("https://Torrentio.strem.fun/abc/manifest.json"), "torrentio.strem.fun/abc")
        self.assertEqual(sc.pool_key("https://x.example/a/"), "x.example/a")
        self.assertEqual(sc.base_from_manifest("https://x.example/a/manifest.json?v=1"), "https://x.example/a")


class PoolRules(unittest.TestCase):
    def test_caps_at_target(self):
        pool = sc.Pool(target=3)
        for i in range(10):
            pool.offer(sc.Entry(manifestUrl=f"https://h{i}.example/manifest.json", baseUrl=f"https://h{i}.example"))
        self.assertEqual(pool.size, 3)
        self.assertTrue(pool.full)

    def test_dedupes_the_same_server(self):
        pool = sc.Pool(target=10)
        entry = sc.Entry(manifestUrl="https://a.example/manifest.json", baseUrl="https://a.example")
        self.assertTrue(pool.offer(entry))
        self.assertFalse(pool.offer(sc.Entry(manifestUrl="https://A.example/manifest.json", baseUrl="https://A.example/")))
        self.assertEqual(pool.size, 1)

    def test_per_host_cap(self):
        pool = sc.Pool(target=100)
        for i in range(10):
            pool.offer(sc.Entry(manifestUrl=f"https://a.example/{i}/manifest.json", baseUrl=f"https://a.example/{i}"))
        self.assertEqual(pool.size, sc.PER_HOST)

    def test_retired_never_returns(self):
        pool = sc.Pool(target=10)
        pool.retired.add("gone.example")
        self.assertFalse(pool.offer(sc.Entry(manifestUrl="https://gone.example/manifest.json",
                                             baseUrl="https://gone.example")))


class CrawlerBehaviour(unittest.TestCase):
    def test_inline_directory_manifest_needs_no_second_request(self):
        body = json.dumps({"addons": [{"manifest": STREAM_MANIFEST, "manifestUrl": "https://ok.example/manifest.json",
                                       "stars": 12}]})
        crawler, pool, stats = crawler_with({"https://stremio-addons.net/api/v0/addons?limit=100&nsfw=exclude&sort_by=stars&order=desc&page=1": body})
        result = crawler.stremio_addons_page(1)
        self.assertEqual(pool.size, 1)
        self.assertEqual(list(pool.entries.values())[0].stars, 12)
        self.assertEqual(result.source, "stremio-addons.net")
        self.assertFalse(result.more)  # fewer than a full page means no page 2

    def test_catalog_only_manifest_is_refused_not_counted(self):
        body = json.dumps({"addons": [{"manifest": CATALOG_ONLY_MANIFEST, "manifestUrl": "https://cat.example/manifest.json"}]})
        crawler, pool, stats = crawler_with({"https://stremio-addons.net/api/v0/addons?limit=100&nsfw=exclude&sort_by=stars&order=desc&page=1": body})
        crawler.stremio_addons_page(1)
        self.assertEqual(pool.size, 0)
        self.assertEqual(stats.refused, 1)

    def test_a_failed_page_is_retried_not_called_empty(self):
        crawler, pool, _ = crawler_with({})
        result = crawler.stremio_addons_page(1)
        self.assertFalse(result.more)
        self.assertTrue(result.retry)

    def test_failed_community_page_is_actually_fetched_again(self):
        url = "https://list.example/readme"
        crawler, _, _ = crawler_with({})
        self.assertTrue(crawler.harvest_page(url, "community").retry)
        crawler.http.pages[url] = "a valid list with no links"
        self.assertFalse(crawler.harvest_page(url, "community").retry)
        self.assertEqual(crawler.http.asked.count(url), 2)

    def test_malformed_directory_items_do_not_abort_page(self):
        url = "https://stremio-addons.net/api/v0/addons?limit=100&nsfw=exclude&sort_by=stars&order=desc&page=1"
        body = json.dumps({"addons": [None, "bad", {"instances": "bad"},
                           {"manifest": STREAM_MANIFEST, "manifestUrl": "https://ok.example/manifest.json", "stars": "unknown"}]})
        crawler, pool, _ = crawler_with({url: body})
        crawler.stremio_addons_page(1)
        self.assertEqual(1, pool.size)
        self.assertEqual(0, next(iter(pool.entries.values())).stars)

    def test_manifest_worker_bug_is_not_reported_as_an_empty_source(self):
        url = "https://list.example/readme"
        manifest = "https://broken.example/manifest.json"
        crawler, _, _ = crawler_with({url: manifest})
        def broken(*args):
            raise RuntimeError("planted worker failure")
        crawler.consider_manifest = broken
        with self.assertRaisesRegex(RuntimeError, "manifest worker crashed"):
            crawler.harvest_page(url, "community")

    def test_a_short_page_has_no_follow_up(self):
        body = json.dumps({"addons": []})
        crawler, pool, _ = crawler_with({"https://stremio-addons.net/api/v0/addons?limit=100&nsfw=exclude&sort_by=stars&order=desc&page=1": body})
        crawler.max_pages = 8
        result = crawler.stremio_addons_page(1)
        self.assertFalse(result.more)
        self.assertIsNone(result.follow)

    def test_readme_links_become_servers(self):
        readme = "Install: https://add.example/manifest.json and https://cat.example/manifest.json\n"
        crawler, pool, _ = crawler_with({
            "https://raw.githubusercontent.com/u/r/main/README.md": readme,
            "https://add.example/manifest.json": json.dumps(STREAM_MANIFEST),
            "https://cat.example/manifest.json": json.dumps(CATALOG_ONLY_MANIFEST),
        })
        self.assertEqual(crawler._readme_scan("https://raw.githubusercontent.com/u/r/main/README.md", 0), 1)
        self.assertEqual(pool.size, 1)

    def test_json_document_manifests_are_found_at_depth(self):
        doc = {"addons": [{"transport": {"url": "https://deep.example/addon"}}], "other": {"manifestUrl": "https://m.example/manifest.json"}}
        found = sc.Crawler._manifests_in_json(doc)
        self.assertIn("https://deep.example/addon/manifest.json", found)
        self.assertIn("https://m.example/manifest.json", found)

    def test_homepage_and_pages_are_probed(self):
        pages = {"https://api.github.com/search/repositories?q=topic%3Astremio-addon&sort=updated&order=desc&per_page=100&page=1":
                 json.dumps({"items": [{"full_name": "Ow/Addon", "homepage": "https://home.example", "stargazers_count": 5,
                                        "default_branch": "main", "description": ""}]}),
                 "https://home.example/manifest.json": json.dumps(STREAM_MANIFEST)}
        crawler, pool, stats = crawler_with(pages)
        crawler.github_page("topic:stremio-addon", 1)
        self.assertTrue(any("home.example/manifest.json" in u for u in crawler.http.asked))
        self.assertTrue(any("ow.github.io" in u for u in crawler.http.asked))
        self.assertEqual(pool.size, 1)


class ThreadingAndSecurity(unittest.TestCase):
    def test_single_source_worker_can_wait_for_an_http_worker(self):
        fetcher = sc.Fetcher(1, 1, sc.Stats())
        try:
            parent = fetcher.submit_source(lambda: fetcher.submit(lambda: 42).result(timeout=2))
            self.assertEqual(42, parent.result(timeout=3))
        finally:
            fetcher.shutdown()

    def test_github_limiter_wont_wait_past_deadline(self):
        limiter = sc.GitHubSearchLimiter(authenticated=True)
        limiter.next_at = time.monotonic() + 10
        self.assertFalse(limiter.wait(time.time() + 0.01))

    def test_token_only_sent_to_github_api_and_not_forwarded(self):
        from urllib.request import HTTPRedirectHandler
        calls = []
        class Response:
            headers = {"Content-Length": "2"}
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, count): return b"{}"
        fetcher = sc.Fetcher(1, 1, sc.Stats(), "example-secret")
        try:
            def fake_open(request, **kwargs):
                calls.append(request)
                # The real API exposes get_content_charset; use an email Message.
                from email.message import Message
                response = Response()
                response.headers = Message()
                return response
            with patch("urllib.request.urlopen", side_effect=fake_open):
                self.assertEqual("{}", fetcher.get("https://api.github.com/repos/example"))
                self.assertEqual("{}", fetcher.get("https://evil.example/path?api.github.com"))
            self.assertEqual("Bearer example-secret", calls[0].get_header("Authorization"))
            self.assertIsNone(calls[1].get_header("Authorization"))
            redirected = HTTPRedirectHandler().redirect_request(calls[0], None, 302, "Found", {}, "https://evil.example/")
            self.assertIsNone(redirected.get_header("Authorization"))
        finally:
            fetcher.shutdown()


class LoopSignals(unittest.TestCase):
    def test_a_full_page_enqueues_the_next_page(self):
        addons = [{"manifest": {**STREAM_MANIFEST, "id": f"a{i}"},
                   "manifestUrl": f"https://h{i}.example/manifest.json"} for i in range(100)]
        body = json.dumps({"addons": addons})
        crawler, pool, _ = crawler_with({"https://stremio-addons.net/api/v0/addons?limit=100&nsfw=exclude&sort_by=stars&order=desc&page=1": body})
        crawler.max_pages = 8
        result = crawler.stremio_addons_page(1)
        self.assertTrue(result.more)
        self.assertIsNotNone(result.follow)
        self.assertEqual(pool.size, min(100, 10))   # pool target caps it
        # The last page must not enqueue a ninth.
        crawler.seen_pages.clear()
        crawler.max_pages = 1
        self.assertFalse(crawler.stremio_addons_page(1).more)

    def test_frontier_seeds_every_github_query_and_page(self):
        crawler, _, _ = crawler_with({})
        import argparse
        args = argparse.Namespace(pages=2, gh_pages=2)
        units = sc.seed_units(crawler, args)
        gh = [u for u in units if u.name.startswith("github")]
        self.assertEqual(len(gh), len(sc.GITHUB_QUERIES) * 2)
        self.assertTrue(any("stremio-addons page 2" == u.name for u in units))


if __name__ == "__main__":
    unittest.main(verbosity=2)
