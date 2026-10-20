# 📺 TV — ZeroPlay × DebridStream prototype

This folder is the new home base for the TV project. It was created inside
`SAVAGE-TECH-CORNER-` because this Arena session is bound to that repo/branch —
the whole `tv/` folder can be copied into the standalone `tv` GitHub repo as-is.

| Path | What it is |
| --- | --- |
| `upstream-zeroplay/` | Fresh sparse clone of [Zer0Spce/ZeroPlay](https://github.com/Zer0Spce/ZeroPlay) (`web/` + `api/` + root files), **unmodified reference**. Full clone (Android/TV/Windows) when needed: `git clone https://github.com/Zer0Spce/ZeroPlay` |
| `zeroplay-debrid/` | **The prototype app** — ZeroPlay's discovery/playback in DebridStream's UI, plus the Sources/FastStream feature set. This is what is deployed. |

**Live prototype:** https://zeroplay-rd.vercel.app
(Vercel project `zeroplay-rd`, root directory `tv/zeroplay-debrid`.)

**Status:** web prototype for review. Next phase after approval: adapt for the
Xiaomi TV Box (3rd gen) — Android TV focus/D-pad navigation, larger type,
remote-friendly player — building on ZeroPlay's TV experience in
`upstream-zeroplay/` and the upstream Android TV source.

See `zeroplay-debrid/README.md` for deploy instructions (Netlify + Vercel),
the source-manager/FastStream feature list, and the honest limitations
(5 GB cap scope, discovery model, embed quality control).
