package com.relife.mobile;

/** JS adapter injected into the existing web app; the web repo needs no Android-specific code. */
public final class OfflineBridgeScript {
    private OfflineBridgeScript() {}

    static final String STOP_MEDIA_SCRIPT = """
        (() => {
          const videos = [...document.querySelectorAll('video')];
          const activeStreams = videos.map(video => video.srcObject).filter(Boolean);
          try {
            if (typeof closeCamera === 'function') closeCamera();
          } catch (_) {}
          activeStreams.forEach(stream => {
            if (stream && typeof stream.getTracks === 'function') {
              stream.getTracks().forEach(track => track.stop());
            }
          });
          videos.forEach(video => {
            video.srcObject = null;
          });
        })();
        """;

    public static final String SCRIPT = """
        (() => {
          const highEnd = __RELIFE_HIGH_END__;
          if (highEnd) {
            const reducedMotion = !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
            let performanceProfile = {
              reducedMotion,
              lowEnd: false,
              motionEnabled: !reducedMotion
            };
            try {
              Object.defineProperty(window, 'RELIFE_PERF', {
                configurable: true,
                get: () => performanceProfile,
                set: value => {
                  const reportedReducedMotion = reducedMotion || !!value?.reducedMotion;
                  performanceProfile = {
                    ...(value && typeof value === 'object' ? value : {}),
                    reducedMotion: reportedReducedMotion,
                    lowEnd: false,
                    motionEnabled: !reportedReducedMotion
                  };
                }
              });
            } catch (_) {}
          }
          const prepareAndroidDocument = () => {
            const root = document.documentElement;
            if (!root) return false;
            root.classList.add('relife-android');
            root.classList.toggle('perf-lite', !highEnd);
            root.classList.toggle('relife-android-high-end', highEnd);
            window.__RELIFE_ANDROID__ = true;
            window.__RELIFE_ANDROID_HIGH_END__ = highEnd;
            if (!document.getElementById('relife-android-interaction')) {
              const androidInteractionStyle = document.createElement('style');
              androidInteractionStyle.id = 'relife-android-interaction';
              androidInteractionStyle.textContent = `
                * { -webkit-tap-highlight-color: transparent; }
                button:focus:not(:focus-visible),
                a:focus:not(:focus-visible),
                [role="button"]:focus:not(:focus-visible) { outline: none !important; }
                html.relife-backgrounded *,
                html.relife-backgrounded *::before,
                html.relife-backgrounded *::after {
                  animation-play-state: paused !important;
                }
              `;
              root.appendChild(androidInteractionStyle);
            }
            let androidLiteStyle = document.getElementById('relife-android-lite-rendering');
            if (!androidLiteStyle) {
              androidLiteStyle = document.createElement('style');
              androidLiteStyle.id = 'relife-android-lite-rendering';
              androidLiteStyle.textContent = `
                html.relife-android { scroll-behavior: auto !important; }
                html.relife-android *,
                html.relife-android *::before,
                html.relife-android *::after {
                  backdrop-filter: none !important;
                  -webkit-backdrop-filter: none !important;
                }
                html.relife-android .app::before,
                html.relife-android .overall-bar-fill::after,
                html.relife-android .criterion-bar-fill::after {
                  display: none !important;
                }
                html.relife-android .login-card,
                html.relife-android .card,
                html.relife-android .fact-card,
                html.relife-android .result-card,
                html.relife-android .record-card,
                html.relife-android .rewards-item,
                html.relife-android .rewards-coupon,
                html.relife-android .app-header,
                html.relife-android .modal,
                html.relife-android .weather-panel,
                html.relife-android .upload-zone {
                  box-shadow: var(--shadow-sm, 0 1px 3px rgba(0, 0, 0, 0.08)) !important;
                  will-change: auto !important;
                }
                html.relife-android .app-header,
                html.relife-android nav.nav,
                html.relife-android .app-nav,
                html.relife-android .login-card,
                html.relife-android .card,
                html.relife-android .fact-card,
                html.relife-android .result-card,
                html.relife-android .record-card,
                html.relife-android .rewards-item,
                html.relife-android .modal,
                html.relife-android .weather-panel {
                  background-color: var(--color-white, #ffffff) !important;
                }
                html.relife-android .upload-zone,
                html.relife-android .rewards-coupon {
                  background-color: var(--color-gray-50, #f8faf8) !important;
                }
                html.relife-android .record-card,
                html.relife-android .rewards-item,
                html.relife-android .rewards-coupon,
                html.relife-android .news-item {
                  content-visibility: auto;
                  contain-intrinsic-size: auto 160px;
                }
                html.relife-android .empty-state-svg {
                  animation: none !important;
                }
              `;
              root.appendChild(androidLiteStyle);
            }
            androidLiteStyle.disabled = highEnd;
            if (highEnd && !window.__RELIFE_HIGH_END_PROFILE_CLEANUP__) {
              const clearLiteProfile = () => root.classList.remove('perf-lite');
              document.addEventListener('DOMContentLoaded', clearLiteProfile, { once: true });
              window.__RELIFE_HIGH_END_PROFILE_CLEANUP__ = true;
            }
            return true;
          };
          if (!prepareAndroidDocument()) {
            const observer = new MutationObserver(() => {
              if (prepareAndroidDocument()) observer.disconnect();
            });
            observer.observe(document, { childList: true, subtree: true });
          }
          if (window.__RELIFE_NATIVE_BRIDGE__) return;
          const nativeBridge = window.RelifeNative;
          if (!nativeBridge) return;
          window.__RELIFE_NATIVE_BRIDGE__ = true;
          const bridgeToken = '__RELIFE_BRIDGE_TOKEN__';
          const originalFetch = window.fetch.bind(window);
          const renderingLabel = () => {
            const lang = String(document.documentElement?.lang || '').toLowerCase();
            if (lang === 'zh-hk' || lang === 'zh-tw' || lang.includes('hant')) return '高畫質模式';
            if (lang.startsWith('zh')) return '高画质模式';
            return 'High quality';
          };
          const syncRenderingSetting = (button, label, enabled) => {
            button.classList.toggle('is-active', enabled);
            button.setAttribute('aria-checked', String(enabled));
            button.setAttribute('aria-label', renderingLabel());
            label.textContent = renderingLabel();
          };
          const installRenderingSetting = () => {
            if (document.getElementById('relife-high-end-row')) return true;
            const themeSelect = document.getElementById('theme-select');
            const themeRow = themeSelect?.closest('.ai-row');
            if (!themeRow?.parentNode) return false;
            const row = document.createElement('div');
            row.id = 'relife-high-end-row';
            row.className = 'ai-row';
            const label = document.createElement('span');
            label.className = 'ai-row-label';
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'ai-switch';
            button.setAttribute('role', 'switch');
            syncRenderingSetting(button, label, highEnd);
            button.addEventListener('click', () => {
              const enabled = button.getAttribute('aria-checked') !== 'true';
              syncRenderingSetting(button, label, enabled);
              let accepted = false;
              try { accepted = !!nativeBridge.setHighEndRendering?.(bridgeToken, enabled); } catch (_) {}
              if (!accepted) syncRenderingSetting(button, label, highEnd);
            });
            row.replaceChildren(label, button);
            themeRow.after(row);
            new MutationObserver(() => syncRenderingSetting(button, label,
              button.getAttribute('aria-checked') === 'true'))
              .observe(document.documentElement, { attributes: true, attributeFilter: ['lang'] });
            return true;
          };
          if (!installRenderingSetting()) {
            const renderingSettingObserver = new MutationObserver(() => {
              if (installRenderingSetting()) renderingSettingObserver.disconnect();
            });
            renderingSettingObserver.observe(document, { childList: true, subtree: true });
          }
          const pendingNativeAgent = new Map();
          const finishNativeAgent = (callbackId, result) => {
            const pending = pendingNativeAgent.get(callbackId);
            if (!pending) return;
            pendingNativeAgent.delete(callbackId);
            clearTimeout(pending.timeout);
            pending.resolve(result && typeof result === 'object' ? result : { error: 'INVALID_NATIVE_RESULT' });
          };
          window.__RELIFE_NATIVE_AGENT_RESULT__ = finishNativeAgent;
          const runNativeAgent = command => new Promise(resolve => {
            const callbackId = typeof crypto?.randomUUID === 'function'
              ? crypto.randomUUID() : Math.random().toString(36).slice(2) + Date.now().toString(36);
            const timeout = setTimeout(
              () => finishNativeAgent(callbackId, { error: 'USER_CONFIRMATION_TIMEOUT' }),
              120000
            );
            pendingNativeAgent.set(callbackId, { resolve, timeout });
            try {
              const raw = nativeBridge.agent(bridgeToken, JSON.stringify({ ...command, callback_id: callbackId }));
              const result = JSON.parse(String(raw || '{}'));
              if (result.status !== 'AWAITING_USER_CONFIRMATION') finishNativeAgent(callbackId, result);
            } catch (_) {
              finishNativeAgent(callbackId, { error: 'INVALID_NATIVE_RESPONSE' });
            }
          });
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
          window.fetch = async (input, init = {}) => {
            const request = input instanceof Request ? input : null;
            const method = String(init.method || request?.method || 'GET').toUpperCase();
            const url = new URL(typeof input === 'string' ? input : input.url, window.location.origin);
            const path = url.pathname + url.search;
            const protectedAction = method === 'POST' && url.origin === window.location.origin
              ? integrityAction(url.pathname) : '';
            let playIntegrity = '';
            let playNonce = '';
            let playAction = '';
            if (protectedAction) {
              playIntegrity = nativeBridge.playIntegrityToken?.(bridgeToken) || '';
              playNonce = nativeBridge.playIntegrityNonce?.(bridgeToken) || '';
              playAction = nativeBridge.playIntegrityAction?.(bridgeToken) || '';
              if (playAction !== protectedAction) {
                playIntegrity = '';
                playNonce = '';
                playAction = '';
              }
              try { nativeBridge.refreshPlayIntegrity?.(bridgeToken, protectedAction); } catch (_) {}
            }
            // Add integrity signals before any cache/network branch. Cached GETs
            // must carry the same headers when they reach the server.
            const sameOrigin = url.origin === window.location.origin;
            if (sameOrigin) {
              init.headers = new Headers(init.headers || request?.headers || {});
              const integrity = nativeBridge.integrityHeader?.(bridgeToken) || '';
              if (integrity) init.headers.set('X-Re-Life-App-Integrity', integrity);
              // Integrity is currently an optional signal. A missing token
              // must never prevent the existing online rewards flow.
              if (protectedAction && playIntegrity) {
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
                if (message === '/device permissions') {
                  const opened = nativeBridge.openAgentPermissions?.(bridgeToken) || false;
                  return new Response(JSON.stringify({
                    status: 'complete',
                    message: opened ? '已打开 Android Agent 权限设置。' : '无法打开 Agent 权限设置。',
                    points: 0
                  }), { status: opened ? 200 : 403, headers: { 'Content-Type': 'application/json' } });
                }
                if (message.startsWith('/device ')) {
                  const command = message.slice('/device '.length).trim();
                  const nativeResult = await runNativeAgent(JSON.parse(command));
                  return new Response(JSON.stringify({
                    status: 'complete',
                    message: '手機沙箱 Agent：' + JSON.stringify(nativeResult),
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
              if (!isOffline()) {
                try { return await originalSave(data); }
                catch (error) { if (navigator.onLine) throw error; }
              }
              const safeData = profileOnly(data);
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
          const installAgentPhoneAdapter = () => {
            const original = window.processAgentPayload;
            if (typeof original !== 'function') return false;
            if (original.__RELIFE_PHONE_ADAPTER__) return true;
            const wrapped = async (payload, depth = 0) => {
              if (payload?.status !== 'requires_action' || payload?.action?.type !== 'get_user_location') {
                return original(payload, depth);
              }
              const originalResolver = window.resolveWeatherCoordinates;
              const nativeResolver = async () => {
                const result = await runNativeAgent({ tool: 'current_location' });
                const latitude = Number(result?.latitude);
                const longitude = Number(result?.longitude);
                return Number.isFinite(latitude) && Number.isFinite(longitude)
                  ? { latitude, longitude } : null;
              };
              window.resolveWeatherCoordinates = nativeResolver;
              try {
                return await original(payload, depth);
              } finally {
                if (window.resolveWeatherCoordinates === nativeResolver) {
                  window.resolveWeatherCoordinates = originalResolver;
                }
              }
            };
            wrapped.__RELIFE_PHONE_ADAPTER__ = true;
            window.processAgentPayload = wrapped;
            return true;
          };
          const timer = setInterval(() => { if (installFbAdapters()) clearInterval(timer); }, 100);
          let agentAdapterAttempts = 0;
          const agentAdapterTimer = setInterval(() => {
            agentAdapterAttempts += 1;
            if (installAgentPhoneAdapter() || agentAdapterAttempts >= 300) clearInterval(agentAdapterTimer);
          }, 100);
          installFbAdapters();
          installAgentPhoneAdapter();
          let lastHapticAt = 0;
          document.addEventListener('click', event => {
            const target = event.target instanceof Element
              ? event.target.closest('button, a, [role="button"], input[type="checkbox"], input[type="radio"]')
              : null;
            if (!target || target.matches(':disabled, [aria-disabled="true"]')) return;
            const now = performance.now();
            if (now - lastHapticAt < 80) return;
            lastHapticAt = now;
            try { nativeBridge.tapFeedback?.(bridgeToken); } catch (_) {}
          }, { capture: true, passive: true });
          window.addEventListener('online', () => { try { nativeBridge.syncNow?.(bridgeToken); } catch (_) {} });
        })();
        """;
}
