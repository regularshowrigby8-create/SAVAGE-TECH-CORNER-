'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const server = fs.readFileSync(path.join(root, 'server.js'), 'utf8');
const finder = fs.readFileSync(path.join(root, 'lib/server-finder/core.js'), 'utf8');

assert(!fs.existsSync(path.join(root, 'api/global-search.js')), 'removed global web-search API is not shipped');
assert(!fs.existsSync(path.join(root, 'lib/vendor/cdrake-se')), 'unused global-search adapters are not vendored');
assert(!server.includes('/api/global-search'), 'local server no longer exposes the removed API');
assert(!html.includes('Global web search leads') && !html.includes('serverGlobal'), 'global web-search leads panel is removed from the UI');
assert(!html.includes('globalSearchQuery') && !html.includes('requestGlobalSearchEngine'), 'the browser no longer schedules global web searches');
assert(html.includes('id="serverFinderProgress"') && finder.includes('crawlGitHub'), 'the bounded GitHub server finder remains available');
assert(html.includes('identifyTitleCandidate') && html.includes('exact-title stream matches are saved'), 'exact title and identity verification remain in place');

console.log('PASS removed global web-search feature; retained GitHub server finder and exact-title verification');
