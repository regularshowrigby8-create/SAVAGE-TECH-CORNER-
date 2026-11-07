# Vendored CDrake-SE engine adapters

Imported from [rodyherrera/CDrake-SE](https://github.com/rodyherrera/CDrake-SE), commit `db68decc20f1fa4c164d1c35df971c8670a56c79` (MIT License; see `LICENSE`). The app uses the upstream AOL, Ask, Bing, Google, Qwant, suggestions, Wikipedia, Yahoo, Yep, and YouTube adapters. `api/global-search.js` is the same-origin, allowlisted relay around those adapters.

ZeroPlay starts one browser-side request worker for each adapter, plus a separate niche-title query pass. No API keys are required. Search providers can rate-limit, change their HTML, or return no results; failures are isolated per engine. Search results are only displayed as title-matched leads. They are not playable server matches, are not installed, and are never fed into the independent continuous provider crawler.
