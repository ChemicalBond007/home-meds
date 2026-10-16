const CACHE = 'home-meds-shell-v1';
self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(['/offline.html', '/icon-192.png'])));
  self.skipWaiting();
});
self.addEventListener('activate', event => {
  event.waitUntil(Promise.all([
    caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))),
    self.clients.claim(),
  ]));
});
self.addEventListener('fetch', event => {
  if (event.request.mode === 'navigate') {
    event.respondWith(fetch(event.request).catch(() => caches.match('/offline.html')));
  }
});
self.addEventListener('push', event => {
  let data;
  try { data = event.data.json(); } catch { return; }
  event.waitUntil(self.registration.showNotification(data.title || '服药提醒', {
    body: data.body, icon: '/icon-192.png', badge: '/icon-192.png', tag: data.tag,
    data: { url: data.url || '/' }, requireInteraction: true,
  }));
});
self.addEventListener('notificationclick', event => {
  event.notification.close();
  const url = new URL(event.notification.data?.url || '/', self.location.origin);
  if (url.origin !== self.location.origin) return;
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    if (windows.length) {
      const client = await windows[0].navigate(url.href);
      if (client) await client.focus();
    } else await self.clients.openWindow(url.href);
  })());
});
