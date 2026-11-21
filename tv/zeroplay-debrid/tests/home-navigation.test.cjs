const fs = require("node:fs");
const vm = require("node:vm");
const assert = require("node:assert/strict");
const path = require("node:path");
const html = fs.readFileSync(path.join(__dirname, "../index.html"), "utf8");
const script = html.slice(html.indexOf("<script>") + 8, html.lastIndexOf("</script>"));
let passed = 0;
function check(condition, label) {
  assert(condition, label);
  passed++;
  console.log("PASS " + label);
}
new vm.Script(script);
check(true, "entire application script compiles");
const start = script.indexOf("async function renderHome(){");
const boundary = script.indexOf("  bindCards(view);", start) + "  bindCards(view);".length;
const homeLayout = script.slice(start, boundary) + "\n}";
const ids = [
  "heroSlot",
  "homeCategoryRail",
  "railTrending",
  "railMovies",
  "railSeries",
  "railAction",
  "railAnimation",
  "railThriller",
  "railTop",
  "railNow",
];
async function main() {
  for (const hasProgress of [false, true]) {
    const item = {
      id: 101,
      type: "movie",
      title: "Saved title",
      poster_path: "/poster.jpg",
      poster_url: "https://example.com/poster.jpg",
      backdrop_url: "https://example.com/backdrop.jpg",
      imdb_id: "tt1234567",
      year: "2025",
      rating: "8.2",
      trailer_key: "trailer",
      logo_url: "https://example.com/logo.png",
      genre_ids: [28],
      overview: "Saved overview",
    };
    let cardsBound = 0,
      mappedItems;
    const ctx = {
      view: { innerHTML: "" },
      watchlist: [item],
      continueItems: () => (hasProgress ? [item] : []),
      continueRailHTML: () => '<section class="continue-rail">Continue Watching</section>',
      skeletonRail: (title) => `<section class="rail">${title}</section>`,
      railHTML: (_title, items) => {
        mappedItems = items;
        return '<section class="watchlist-rail"></section>';
      },
      bindCards: () => {
        cardsBound++;
      },
    };
    vm.createContext(ctx);
    vm.runInContext(homeLayout, ctx);
    await ctx.renderHome();
    const markup = ctx.view.innerHTML;
    check(
      (markup.match(/class="continue-rail"/g) || []).length === (hasProgress ? 1 : 0),
      `resume rail is ${hasProgress ? "present once" : "absent"} for ${hasProgress ? "saved" : "empty"} progress`,
    );
    if (hasProgress)
      check(
        markup.indexOf("continue-rail") < markup.indexOf('id="heroSlot"'),
        "resume markup comes before the featured slot",
      );
    for (const id of ids)
      check(
        (markup.match(new RegExp(`id="${id}"`, "g")) || []).length === 1,
        `${id} loading target is present exactly once`,
      );
    check(cardsBound === 1, "initial layout binds cards once");
    const mapped = mappedItems[0];
    check(
      mapped.media_type === "movie" && mapped.title === item.title && mapped.name === undefined,
      "watchlist mapping preserves the existing media_type contract",
    );
    check(
      [
        "poster_path",
        "poster_url",
        "backdrop_url",
        "imdb_id",
        "trailer_key",
        "logo_url",
        "genre_ids",
        "overview",
      ].every((key) => JSON.stringify(mapped[key]) === JSON.stringify(item[key])),
      "watchlist mapping preserves all existing artwork, identity and metadata fields",
    );
    check(
      mapped.release_date === "2025-01-01" &&
        mapped.first_air_date === "2025-01-01" &&
        mapped.vote_average === 8.2,
      "watchlist mapping preserves year and numeric rating",
    );
    ctx.watchlist = [];
    await ctx.renderHome();
    check(!ctx.view.innerHTML.includes("watchlist-rail"), "empty watchlist adds no empty rail");
  }
  const home = script.slice(boundary, script.indexOf("\nconst CATEGORY_GROUPS=", boundary));
  for (const action of ["heroPlay", "heroDetails", "heroServers", "heroList"])
    check(home.includes(`$("#${action}").onclick`), `original ${action} wiring is retained`);
  check(
    home.includes("const fill=async(") &&
      home.includes("homeCategoryRailHTML(homePools)") &&
      home.includes("startBackdropPreview(heroTrailer,remembered,"),
    "original fill helper, categories and trailer setup remain in renderHome",
  );
  check(!/const heroLogo\s*=\s*heroLogo/.test(script), "no heroLogo temporal-dead-zone error");
  const css = html.slice(html.indexOf("<style>") + 7, html.indexOf("</style>"));
  check(
    !/\.poster-card:focus-visible\s+\.poster-wrap::(?:after|before)\s*\{/.test(css),
    "no false poster-focus Play pseudo-elements survive",
  );
  check(
    !/body\.android-tv\s+#hero(?:Servers|List)[^{]*\{[^}]*display:\s*none/.test(css),
    "TV hero Servers and Watchlist actions are not hidden",
  );
  check(
    !/TV_HOME_COMMANDS|tvHomeCommandPanel|originalRenderForTvHome|menu-drawer\.opentransform|\.zp-hero/.test(
      html,
    ),
    "abandoned command bar, invalid drawer state and unused replacement hero classes are absent",
  );
  check(
    (script.match(/closest\("\[data-menu-route\]"\)/g) || []).length === 1,
    "one delegated route activation handler serves sidebar and drawer",
  );
  check(
    /\.menu-drawer\.open\s*\{[^}]*visibility:\s*visible/.test(css),
    "drawer retains a functional visible open state",
  );
  console.log(`\n${passed} Home/navigation regression checks passed`);
}
main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
