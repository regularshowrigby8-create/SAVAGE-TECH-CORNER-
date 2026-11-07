const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");

const html = fs.readFileSync(path.join(__dirname, "..", "index.html"), "utf8");
const checks = [
  ["search uses a 350ms debounce", /setTimeout\(\(\)=>runSearch\(\),350\)/.test(html)],
  ["search fans out concurrently to Cinemeta, TVmaze and AniList", /Promise\.all\(\[cm\("movie"\),cm\("tv"\),tvmaze,anilist\]\)/.test(html) && html.includes("api.tvmaze.com/search/shows") && html.includes("graphql.anilist.co")],
  ["search aborts stale requests and checks a sequence before rendering", html.includes("searchController.abort()") && html.includes("if(sequence!==searchSequence)return")],
  ["search merges and de-duplicates results by normalized title, year and type", html.includes('const key=title+"|"+year+"|"+type')],
  ["search offers movie and TV filters", html.includes('data-f="movie"') && html.includes('data-f="tv"') && html.includes('data-f="all"')],
  ["recent searches persist locally and can be selected", html.includes("SEARCH_RECENTS_KEY") && html.includes("data-search-recent")],
  ["catalogue-native results can open ZeroPlay title details", html.includes("searchResultItems.get(keyOf(type,Number(id)))") && html.includes("search_provider_id")],
  ["player has an explicit hide button and a restore affordance", html.includes('id="btnHidePlayerChrome"') && html.includes('id="btnRestorePlayerChrome"') && html.includes("function hidePlayerChrome()")],
  ["paused resume action shelf no longer overlays embedded controls", !html.includes("player-action-shelf") && !html.includes("btnPlayerResume")],
  ["embedded-player mode hides ZeroPlay chrome and disables center-play overlays", /if\(limited\)\{[\s\S]*?hidePlayerChrome\(\);/.test(html) && html.includes('center.hidden=embedControls||!st.playback.paused')],
  ["hidden chrome stays hidden on pointer movement and touch until explicitly restored", html.includes('overlay.addEventListener("mousemove",()=>showPlayerChrome(false));') && html.includes('overlay.addEventListener("touchstart",()=>showPlayerChrome(false),{passive:true})') && html.includes('on("btnRestorePlayerChrome","click",()=>showPlayerChrome(true,true))')],
  ["search-result details fall back to cached result metadata if the detail API fails", html.includes('const hasLocalTitle=!!(localItem&&localItem.id') && html.includes('if(hasLocalTitle&&(!remoteUsable||!remoteMatchesLocal)){ d=localItem; usedLocalFallback=true; }')],
];

for (const [name, passed] of checks) {
  assert.ok(passed, `FAIL ${name}`);
  console.log(`PASS ${name}`);
}
console.log(`\n${checks.length} search/player-chrome checks passed`);
