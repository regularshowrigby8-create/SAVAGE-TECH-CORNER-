# Self-hosted adaptive streaming engines

Imported verbatim from the official npm packages (2026-10-21) so HLS/DASH
playback never depends on a third-party CDN or on the hosted FastStream web
build being reachable. Loaded lazily by `loadVendorScript()` only when an
HLS/DASH stream actually needs an MSE engine.

| File | Project | Version | Source | License |
|---|---|---|---|---|
| `hls.min.js` | hls.js (video-dev) | 1.7.3 | npm `hls.js@1.7.3`, `dist/hls.min.js` | Apache-2.0 (`hls.js-LICENSE.txt`) |
| `dash.all.min.js` | dash.js (Dash-Industry-Forum) | 5.2.1 | npm `dashjs@5.2.1`, `dist/modern/umd/dash.all.min.js` | BSD (`dash.js-LICENSE.txt`) |

Role in ZeroPlay (see `attachNativeSource` in `index.html`):

- Direct files → built-in `<video>` (hardware path).
- HLS → native platform HLS when the WebView offers it, else hls.js (MSE).
- DASH → FastStream EmbedAPI first on TV; dash.js (MSE) is the guaranteed
  fallback inside our own D-pad-controlled player.

To update: `npm pack hls.js@<version> dashjs@<version>`, replace the two
files, bump this table, run `npm test`.
