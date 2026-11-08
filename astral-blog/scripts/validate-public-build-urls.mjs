for (const key of ['NEXT_PUBLIC_SITE_URL', 'NEXT_PUBLIC_ADMIN_URL']) {
  let url;
  try { url = new URL(process.env[key] || ''); } catch { throw new Error(`${key} must be an absolute public HTTPS URL`); }
  if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash
      || ['localhost', '127.0.0.1', '::1', '[::1]'].includes(url.hostname)) {
    throw new Error(`${key} must be a public HTTPS origin without credentials, path, query or fragment`);
  }
}
