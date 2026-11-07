'use strict';

const api = require('../../api/find');

exports.handler = async event => {
  let body = null;
  if (event.body) {
    try { body = JSON.parse(event.body); } catch { body = event.body; }
  }
  const req = {
    method: event.httpMethod,
    query: event.queryStringParameters || {},
    body,
    headers: Object.fromEntries(Object.entries(event.headers || {}).map(([key, value]) => [key.toLowerCase(), value])),
    socket: { remoteAddress: event.headers && (event.headers['x-nf-client-connection-ip'] || event.headers['x-forwarded-for']) || 'unknown' },
  };
  let statusCode = 200;
  const headers = {};
  let responseBody = '';
  const res = {
    setHeader(name, value) { headers[name] = value; },
    status(code) { statusCode = code; return this; },
    json(value) { responseBody = JSON.stringify(value); return value; },
  };
  await api(req, res);
  return { statusCode, headers, body: responseBody };
};
