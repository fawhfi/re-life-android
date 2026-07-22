package com.relife.mobile;

/** JS adapter injected into the existing web app; the web repo needs no Android-specific code. */
public final class OfflineBridgeScript {
    private OfflineBridgeScript() {}
    public static final String SCRIPT = """
        (() => {
          if (window.__RELIFE_NATIVE_BRIDGE__) return;
          const nativeBridge = window.RelifeNative;
          if (!nativeBridge) return;
          window.__RELIFE_NATIVE_BRIDGE__ = true;
          const originalFetch = window.fetch.bind(window);
          const cacheable = path => [
            '/api/users/me', '/api/records', '/api/news', '/api/fact',
            '/api/recycling/nearby', '/api/weather/header', '/api/agent/conversations'
          ].some(prefix => path === prefix || path.startsWith(prefix + '?') || path.startsWith(prefix + '/'));
          const keyFor = url => 'get:' + url;
          const responseFromCache = (url, value) => new Response(value, {
            status: 200, headers: { 'Content-Type': 'application/json', 'X-ReLife-Cache': 'offline' }
          });
          window.fetch = async (input, init = {}) => {
            const request = input instanceof Request ? input : null;
            const method = String(init.method || request?.method || 'GET').toUpperCase();
            const url = new URL(typeof input === 'string' ? input : input.url, window.location.origin);
            const path = url.pathname + url.search;
            if (method === 'POST' && url.pathname === '/api/agent/messages') {
              try {
                const rawBody = init.body || (request && await request.clone().text()) || '';
                const parsed = JSON.parse(rawBody || '{}');
                const message = String(parsed.message || '').trim();
                if (message.startsWith('/device ')) {
                  const command = message.slice('/device '.length).trim();
                  const nativeResult = nativeBridge.agent(command);
                  return new Response(JSON.stringify({
                    status: 'complete',
                    message: '手機沙箱 Agent：' + nativeResult,
                    points: 0
                  }), { status: 200, headers: { 'Content-Type': 'application/json' } });
                }
              } catch (_) { /* let the normal server Agent handle malformed messages */ }
            }
            if (method === 'GET' && cacheable(url.pathname)) {
              try {
                const response = await originalFetch(input, init);
                if (response.ok) {
                  const copy = response.clone();
                  copy.text().then(body => nativeBridge.cachePut(keyFor(path), body)).catch(() => {});
                }
                return response;
              } catch (error) {
                const cached = nativeBridge.cacheGet(keyFor(path));
                if (cached) return responseFromCache(path, cached);
                throw error;
              }
            }
            return originalFetch(input, init);
          };
          const queueable = (method, path) => !!nativeBridge.enqueueMutation && (
            (method === 'POST' && path === '/api/records') ||
            (method === 'PATCH' && path === '/api/users/me') ||
            (method === 'DELETE' && (path === '/api/records' || path.startsWith('/api/records/')))
          );
          const json = value => JSON.stringify(value ?? {});
          const isOffline = () => typeof nativeBridge.isOnline !== 'function' || !nativeBridge.isOnline();
          const queue = (method, path, body) => nativeBridge.enqueueMutation(method, path, body);
          const installFbAdapters = () => {
            const fb = window.FB;
            if (!fb || fb.__RELIFE_OFFLINE_ADAPTER__) return !!fb;
            const originalAdd = fb.addItem?.bind(fb);
            const originalSave = fb.saveUserData?.bind(fb);
            const originalDelete = fb.deleteItem?.bind(fb);
            const originalClear = fb.clearAllItems?.bind(fb);
            if (originalAdd) fb.addItem = async item => {
              if (!isOffline()) {
                try { return await originalAdd(item); }
                catch (error) { if (!navigator.onLine) {
                  const id = queue('POST', '/api/records', json(item));
                  if (id) return { id: 'offline-' + id, image_url: item?.image_url || item?.photoUrl || '' };
                } throw error; }
              }
              const id = queue('POST', '/api/records', json(item));
              if (!id) throw new Error('OFFLINE_QUEUE_UNAVAILABLE');
              return { id: 'offline-' + id, image_url: item?.image_url || item?.photoUrl || '' };
            };
            if (originalSave) fb.saveUserData = async data => {
              if (!isOffline()) {
                try { return await originalSave(data); }
                catch (error) { if (navigator.onLine) throw error; }
              }
              const id = queue('PATCH', '/api/users/me', json(data));
              if (!id) throw new Error('OFFLINE_QUEUE_UNAVAILABLE');
              return { ...(window.__relifeCachedUser || {}), ...data };
            };
            if (originalDelete) fb.deleteItem = async id => {
              if (!isOffline()) return originalDelete(id);
              const queued = queue('DELETE', '/api/records/' + encodeURIComponent(id), '');
              if (!queued) throw new Error('OFFLINE_QUEUE_UNAVAILABLE');
            };
            if (originalClear) fb.clearAllItems = async () => {
              if (!isOffline()) return originalClear();
              const queued = queue('DELETE', '/api/records', '');
              if (!queued) throw new Error('OFFLINE_QUEUE_UNAVAILABLE');
            };
            const originalLogout = fb.logout?.bind(fb);
            if (originalLogout) fb.logout = async (...args) => {
              try { return await originalLogout(...args); }
              finally { nativeBridge.clearOfflineData?.(); }
            };
            fb.__RELIFE_OFFLINE_ADAPTER__ = true;
            return true;
          };
          const timer = setInterval(() => { if (installFbAdapters()) clearInterval(timer); }, 100);
          installFbAdapters();
          window.addEventListener('online', () => { try { nativeBridge.syncNow?.(); } catch (_) {} });
        })();
        """;
}
