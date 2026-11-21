/* One place that knows how to pull the application script out of index.html.

   It used to be `html.slice(html.indexOf('<script>'), html.lastIndexOf('</script>'))`
   in seven test files. That assumed exactly one script, so adding the boot
   watchdog (a separate, earlier <script>) silently made every one of those
   tests try to compile two scripts as one. The app script is now identified by
   its own opening, not by being the only one. */
const fs = require('fs');
const path = require('path');

const OPENING = '<script>\n"use strict";';

function readIndex() {
  return fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
}

function appScript(html) {
  html = html || readIndex();
  const start = html.indexOf(OPENING);
  if (start < 0) throw new Error('app script opening not found in index.html');
  const from = start + '<script>'.length;
  const end = html.indexOf('</script>', from);
  if (end < 0) throw new Error('app script has no closing tag');
  return html.slice(from, end);
}

module.exports = { appScript, readIndex };
