/* Netlify twin of api/global-resolve.js.
   The handler is already platform-neutral (it returns {statusCode,payload} and
   adapts to res.status/res.json when present), so this file only has to require
   it and hand over. Keep the logic in one place: the resolver is shared. */
"use strict";
const handler = require("../../api/global-resolve.js");
const { URL } = require("node:url");

exports.handler = async (event) => {
  const query = {};
  for (const [key, value] of new URLSearchParams(event.queryStringParameters || {})) {
    query[key] = value;
  }
  let body = {};
  if (event.body) {
    try {
      body = JSON.parse(event.body);
    } catch (error) {
      body = {};
    }
  }
  const req = {
    method: event.httpMethod || "GET",
    query: Object.assign(query, body),
    body,
    headers: event.headers || {}
  };
  const res = {
    statusCode: 200,
    headers: {},
    setHeader(key, value) {
      this.headers[key] = value;
    },
    status(code) {
      this.statusCode = code;
      return this;
    },
    json(payload) {
      this.body = JSON.stringify(payload);
      return this;
    },
    end(payload) {
      this.body = payload;
      return this;
    }
  };
  await handler(req, res);
  return {
    statusCode: res.statusCode || 200,
    headers: Object.assign({ "Content-Type": "application/json", "Cache-Control": "no-store" }, res.headers),
    body: res.body || "{}"
  };
};
