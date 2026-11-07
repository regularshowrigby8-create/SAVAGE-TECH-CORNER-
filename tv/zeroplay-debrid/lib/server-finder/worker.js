'use strict';

const { parentPort, workerData } = require('node:worker_threads');
const { crawlSource } = require('./core');

(async () => {
  const { sourceId, identity, options } = workerData || {};
  const result = await crawlSource(sourceId, identity, options || {});
  parentPort.postMessage(result);
})().catch(error => {
  parentPort.postMessage({
    id: workerData && workerData.sourceId || 'unknown', status: 'failed',
    seen: 0, checked: 0, candidates: 0, records: [],
    error: String(error && error.message || 'Worker failed').slice(0, 140), elapsedMs: 0,
  });
});
