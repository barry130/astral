'use strict';
// Scope claim: application fetch only, not an OS packet-capture/zero-egress certificate.
const fetch = global.fetch;
global.fetch = (input, init) => {
  const url = new URL(typeof input === 'string' || input instanceof URL ? input : input.url);
  if (
    url.protocol !== 'http:' ||
    url.hostname !== '127.0.0.1' ||
    !['41890', '41891'].includes(url.port)
  ) {
    throw new Error(`External application fetch denied by isolated gate: ${url.origin}`);
  }
  return fetch(input, init);
};
