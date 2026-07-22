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
          const bridgeToken = '__RELIFE_BRIDGE_TOKEN__';
          const originalFetch = window.fetch.bind(window);
          const cacheable = path => [
            '/api/users/me', '/api/records', '/api/news', '/api/fact',
            '/api/recycling/nearby', '/api/weather/header', '/api/agent/conversations'
          ].some(prefix => path === prefix || path.startsWith(prefix + '?') || path.startsWith(prefix + '/'));
          const keyFor = url => 'get:' + url;
          const responseFromCache = (url, value) => new Response(value, {
            status: 200, headers: { 'Content-Type': 'application/json', 'X-ReLife-Cache': 'offline' }
          });
          const integrityAction = path => path === '/api/rewards/redeem' ? 'reward_redeem'
            : path === '/api/rewards/prove-swap' ? 'prove_swap' : '';
          const waitForIntegrity = async action => {
            if (!action || !nativeBridge.refreshPlayIntegrity?.(bridgeToken, action)) return false;
            for (let attempt = 0; attempt < 60; attempt++) {
              const value = nativeBridge.playIntegrityToken?.(bridgeToken) || '';
              const currentAction = nativeBridge.playIntegrityAction?.(bridgeToken) || '';
              if (value && currentAction === action) return true;
              await new Promise(resolve => setTimeout(resolve, 100));
            }
            return false;
          };
          window.fetch = async (input, init = {}) => {
            const request = input instanceof Request ? input : null;
            const method = String(init.method || request?.method || 'GET').toUpperCase();
            const url = new URL(typeof input === 'string' ? input : input.url, window.location.origin);
            const path = url.pathname + url.search;
            const protectedAction = method === 'POST' && url.origin === window.location.origin
              ? integrityAction(url.pathname) : '';
            if (protectedAction && !(await waitForIntegrity(protectedAction))) {
              return new Response(JSON.stringify({ error: 'INTEGRITY_REQUIRED' }), {
                status: 403, headers: { 'Content-Type': 'application/json' }
              });
            }
            // Add integrity signals before any cache/network branch. Cached GETs
            // must carry the same headers when they reach the server.
            const sameOrigin = url.origin === window.location.origin;
            if (sameOrigin) {
              init.headers = new Headers(init.headers || request?.headers || {});
              const integrity = nativeBridge.integrityHeader?.(bridgeToken) || '';
              if (integrity) init.headers.set('X-Re-Life-App-Integrity', integrity);
              // Play Integrity tokens are one-use, action-bound credentials.
              // Attach them only after waitForIntegrity() for a protected
              // action; never replay them on ordinary GETs.
              if (protectedAction) {
                const playIntegrity = nativeBridge.playIntegrityToken?.(bridgeToken) || '';
                const playNonce = nativeBridge.playIntegrityNonce?.(bridgeToken) || '';
                const playAction = nativeBridge.playIntegrityAction?.(bridgeToken) || '';
                if (playIntegrity) init.headers.set('X-Re-Life-Play-Integrity', playIntegrity);
                if (playNonce) init.headers.set('X-Re-Life-Play-Integrity-Nonce', playNonce);
                if (playAction) init.headers.set('X-Re-Life-Play-Integrity-Action', playAction);
              }
            }
            if (method === 'POST' && url.pathname === '/api/agent/messages') {
              try {
                const rawBody = init.body || (request && await request.clone().text()) || '';
                const parsed = JSON.parse(rawBody || '{}');
                const message = String(parsed.message || '').trim();
                if (message.startsWith('/device ')) {
                  const command = message.slice('/device '.length).trim();
                  const nativeResult = nativeBridge.agent(bridgeToken, command);
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
                  copy.text().then(body => nativeBridge.cachePut(bridgeToken, keyFor(path), body)).catch(() => {});
                }
                return response;
              } catch (error) {
                const cached = nativeBridge.cacheGet(bridgeToken, keyFor(path));
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
          // The existing web UI currently sends reward counters through
          // saveUserData. They are server-owned values and must never be
          // accepted from an offline Android queue. Keep only the avatar
          // fields that are genuinely user-editable.
          const profileOnly = data => {
            if (!data || typeof data !== 'object') return {};
            const result = {};
            if (typeof data.photoUrl === 'string') result.photoUrl = data.photoUrl;
            if (typeof data.photo_url === 'string') result.photo_url = data.photo_url;
            return result;
          };
          const isOffline = () => typeof nativeBridge.isOnline !== 'function' || !nativeBridge.isOnline(bridgeToken);
          const queue = (method, path, body) => nativeBridge.enqueueMutation(bridgeToken, method, path, body);
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
              const safeData = profileOnly(data);
              if (!isOffline()) {
                try { return await originalSave(safeData); }
                catch (error) { if (navigator.onLine) throw error; }
              }
              const id = queue('PATCH', '/api/users/me', json(safeData));
              if (!id) throw new Error('OFFLINE_QUEUE_UNAVAILABLE');
              return { ...(window.__relifeCachedUser || {}), ...safeData };
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
              finally { nativeBridge.clearOfflineData?.(bridgeToken); }
            };
            fb.__RELIFE_OFFLINE_ADAPTER__ = true;
            return true;
          };
          const timer = setInterval(() => { if (installFbAdapters()) clearInterval(timer); }, 100);
          installFbAdapters();
          window.addEventListener('online', () => { try { nativeBridge.syncNow?.(bridgeToken); } catch (_) {} });
        })();
        """;
}
