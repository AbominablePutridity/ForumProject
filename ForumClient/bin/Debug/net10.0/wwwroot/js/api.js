'use strict';

const JFC_PENDING = new Map();
let jfcSeq = 1;

/**
 * Кроссплатформенный мост между JS и C#:
 *   Windows : window.chrome.webview.postMessage   (ответы — событие 'message')
 *   WebKit  : window.webkit.messageHandlers.webview.postMessage (Linux/macOS)
 *   fallback: window.external.sendMessage
 *
 * Ответы C# → JS приходят через PostWebMessageAsString, который на Windows
 * приходит как событие 'message' канала chrome.webview, а на Linux/WebKit —
 * через глобальную функцию __dispatchMessageCallback(JSON-строка).
 * Обе ветки сводятся к handleNativeReply(raw).
 */
function nativeAvailable() {
    return Boolean(
        (window.chrome && window.chrome.webview) ||
        (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.webview) ||
        (window.external && typeof window.external.sendMessage === 'function')
    );
}

function postToNative(obj) {
    if (window.chrome && window.chrome.webview) {
        // ВАЖНО: шлём ТОЛЬКО JSON-строкой. WebView2 Runtime 151 (апрель 2026)
        // перестал доставлять объектные postMessage в хост (WebMessageReceived
        // молчит), строки проходят. Ветка webkit ниже уже слала строкой.
        window.chrome.webview.postMessage(JSON.stringify(obj));
        return;
    }
    if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.webview) {
        window.webkit.messageHandlers.webview.postMessage(JSON.stringify(obj));
        return;
    }
    if (window.external && typeof window.external.sendMessage === 'function') {
        window.external.sendMessage(JSON.stringify(obj));
    }
}

function handleNativeReply(raw) {
    if (raw == null) return;
    let msg = raw;
    if (typeof msg === 'string') {
        if (msg.trim() === '') return;
        try { msg = JSON.parse(msg); } catch (e) { return; }
    }
    if (!msg || typeof msg.reqId !== 'number') return;

    jfcChannelReady = true;

    const pending = JFC_PENDING.get(msg.reqId);
    if (!pending) return;

    JFC_PENDING.delete(msg.reqId);

    if (msg.ok) pending.resolve(msg.data);
    else pending.reject(new Error(msg.error || 'Ошибка запроса'));
}

// Канал JS→C# «просыпается» на старте: первый postMessage в момент загрузки
// документа может потеряться в гонке с инициализацией WebView2. Пока ни одного
// ответа не получено, первый вызов переотправляется один раз через 1.5 с.
let jfcChannelReady = false;

// Windows: ответы приходят событием 'message' канала chrome.webview.
(function () {
    if (!(window.chrome && window.chrome.webview)) return;
    window.chrome.webview.addEventListener('message', (ev) => handleNativeReply(ev.data));
})();

// WebKit/Linux: native вызывает window.__dispatchMessageCallback(JSON-строка).
if (typeof window.__dispatchMessageCallback === 'undefined') {
    window.__dispatchMessageCallback = function (message) { handleNativeReply(message); };
}

function api(action, args = {}) {
    return new Promise((resolve, reject) => {
        if (!nativeAvailable()) {
            reject(new Error('Страница открыта вне приложения ForumClient'));
            return;
        }

        const reqId = jfcSeq++;
        const pending = { resolve, reject };
        JFC_PENDING.set(reqId, pending);

        setTimeout(() => {
            if (JFC_PENDING.has(reqId)) {
                JFC_PENDING.delete(reqId);
                reject(new Error('Приложение не ответило на запрос "' + action + '"'));
            }
        }, 30000);

        postToNative({ reqId, action, args });

        if (!jfcChannelReady) {
            setTimeout(() => {
                if (JFC_PENDING.has(reqId)) {
                    LogChannel('повтор первого запроса ' + action);
                    postToNative({ reqId, action, args });
                }
            }, 1500);
        }
    });
}

function LogChannel(msg) {
    try {
        if (typeof console !== 'undefined' && console.log) console.log('[api] ' + msg);
    } catch (e) { /* ignore */ }
}

window.addEventListener('error', (e) => {
    toast('Ошибка скрипта: ' + (e.message || 'неизвестная'), 'error');
});

window.addEventListener('unhandledrejection', (e) => {
    const reason = e.reason && e.reason.message ? e.reason.message : String(e.reason);
    toast('Ошибка: ' + reason, 'error');
});

// ---------------------------------------------------------------------------
// Медиа через мост: attachment.download → base64 → Blob → objectURL.
// Медиа-файлы грузятся через C#-мост (не HTTP), т.к. WebView.Avalonia не
// перехватывает HTTP-запросы. objectURL кэшируются по id вложения.
// ---------------------------------------------------------------------------
const JFC_BLOB = new Map();   // attachmentId -> objectURL
const JFC_BLOB_PENDING = new Map(); // attachmentId -> Promise<objectURL>

function base64ToBytes(base64) {
    if (!base64) return new Uint8Array(0);
    const bin = atob(base64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
}

/** Возвращает objectURL для вложения (скачивает при необходимости). */
async function mediaSrc(att) {
    const id = Number(att && att.id);
    if (!id) return '';

    if (JFC_BLOB.has(id)) return JFC_BLOB.get(id);
    if (JFC_BLOB_PENDING.has(id)) return JFC_BLOB_PENDING.get(id);

    const promise = (async () => {
        const res = await api('attachment.download', { attachmentId: id });
        const mime = (res && res.mimeType) || 'application/octet-stream';
        const blob = new Blob([base64ToBytes(res && res.dataBase64)], { type: mime });
        const url = URL.createObjectURL(blob);
        JFC_BLOB.set(id, url);
        return url;
    })();

    JFC_BLOB_PENDING.set(id, promise);

    try {
        return await promise;
    } finally {
        JFC_BLOB_PENDING.delete(id);
    }
}

/** Освобождает objectURL вложения (вызывать при замене списка/размонтировании). */
function destroyMediaUrl(attOrId) {
    const id = Number(attOrId && (attOrId.id !== undefined ? attOrId.id : attOrId));
    const url = JFC_BLOB.get(id);
    if (url) {
        URL.revokeObjectURL(url);
        JFC_BLOB.delete(id);
    }
}

function esc(value) {
    return String(value ?? '').replace(/[&<>"']/g, (ch) => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[ch]));
}

function fmtDate(sqlDate) {
    return sqlDate ? String(sqlDate).slice(0, 16) : '';
}

function truncate(text, limit = 240) {
    const s = String(text ?? '');
    return s.length > limit ? s.slice(0, limit).trimEnd() + '…' : s;
}

function qs(name) {
    return new URLSearchParams(location.search).get(name);
}

function toast(message, kind = '') {
    const el = document.createElement('div');
    el.className = 'toast' + (kind ? ' ' + kind : '');
    el.textContent = message;
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 3200);
}

async function ensureSession({ required = true } = {}) {
    let session = null;

    try { session = await api('session.get'); } catch (e) { /* ignore */ }

    if (!session) {
        const login = localStorage.getItem('jfc_login');
        const password = localStorage.getItem('jfc_password');

        if (login && password) {
            try {
                await api('auth.login', { login, password });
                session = await api('session.get');
            } catch (e) { /* ignore */ }
        }
    }

    if (!session && required) {
        location.href = 'login.html';
        return null;
    }

    return session;
}

async function rememberCredentials(login, password) {
    localStorage.setItem('jfc_login', login);
    localStorage.setItem('jfc_password', password);
}

async function logout() {
    try { await api('auth.logout'); } catch (e) { /* ignore */ }

    localStorage.removeItem('jfc_login');
    localStorage.removeItem('jfc_password');
    location.href = 'login.html';
}

function renderNav(activePage, user) {
    const nav = document.getElementById('topbar');
    if (!nav) return;

    const links = [
        ['feed.html', 'Лента'],
        ['catalog.html', 'Группы'],
        ['create-group.html', '+ Группа'],
        ['create-post.html', '+ Пост']
    ];

    nav.innerHTML = `
      <div class="topbar-inner">
        <a class="brand" href="feed.html">JCore Forum</a>
        ${links.map(([href, label]) =>
            `<a class="nav-link${activePage === href ? ' active' : ''}" href="${href}">${label}</a>`
        ).join('')}
        <span class="nav-user">${esc(user ? user.name + ' ' + user.surname : '')}</span>
        <button class="btn btn-sm" style="background:transparent;color:#b9c1dd;border-color:#39415a"
                onclick="logout()">Выйти</button>
      </div>`;
}

function renderPager(container, page, pages, onPage) {
    container.innerHTML = '';

    if (!pages || pages <= 0) return;

    const prev = document.createElement('button');
    prev.className = 'btn btn-sm';
    prev.textContent = '← Назад';
    prev.disabled = page <= 1;
    prev.addEventListener('click', () => onPage(page - 1));
    container.appendChild(prev);

    const info = document.createElement('span');
    info.className = 'pager-info';
    info.textContent = `Страница ${page} из ${pages}`;
    container.appendChild(info);

    const next = document.createElement('button');
    next.className = 'btn btn-sm';
    next.textContent = 'Вперёд →';
    next.disabled = page >= pages;
    next.addEventListener('click', () => onPage(page + 1));
    container.appendChild(next);
}

function showSpinner(el) {
    el.innerHTML = `<div class="spinner-wrap"><div class="spinner"></div>Загрузка…</div>`;
}

function showEmpty(el, message) {
    el.innerHTML = `<div class="empty-state">${esc(message)}</div>`;
}

function showError(el, message) {
    el.innerHTML = `<div class="alert alert-error">${esc(message)}</div>`;
}

function postCardHtml(post) {
    return `
      <article class="card">
        <div class="card-head">
          <h3><a href="post.html?id=${post.id}">${esc(post.title)}</a></h3>
          <span class="muted">${fmtDate(post.createdAt)}</span>
        </div>
        <p class="post-preview">${esc(truncate(post.body))}</p>
        <div class="meta-row">
          <a class="badge" href="group.html?id=${post.groupId}">${esc(post.groupTitle)}</a>
          <span>автор: <b>${esc(post.authorLogin)}</b></span>
          <span>комментарии: ${post.commentsCount}</span>
          <span>вложения: ${post.attachmentsCount}</span>
        </div>
      </article>`;
}

function groupCardHtml(group, extraActionsHtml = '') {
    return `
      <article class="card" id="group-card-${group.id}">
        <div class="card-head">
          <h3><a href="group.html?id=${group.id}">${esc(group.title)}</a></h3>
          <span class="muted">создана ${fmtDate(group.createdAt)}</span>
        </div>
        <p class="post-preview">${esc(truncate(group.description, 200))}</p>
        <div class="meta-row">
          <span>подписчики: ${group.subscribersCount}</span>
          <span>посты: ${group.postsCount}</span>
          <span>владелец: <b>${esc(group.ownerLogin)}</b></span>
        </div>
        <div class="actions-row" data-group-id="${group.id}">
          ${extraActionsHtml}
        </div>
      </article>`;
}

/**
 * Загружает один файл к посту через C#-мост (base64 в args), т.к. WebView.Avalonia
 * не перехватывает HTTP. Файл читается как base64 и передаётся в post.uploadFiles.
 */
async function uploadFileToPost(postId, file) {
    const base64 = await readFileAsBase64(file);
    const res = await api('post.uploadFiles', {
        postId,
        files: [{ name: file.name, dataBase64: base64 }]
    });
    if (!res) throw new Error('Сервер не вернул ответ');
}

function readFileAsBase64(file) {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => {
            const data = String(reader.result || '').split(',')[1] || '';
            resolve(data);
        };
        reader.onerror = () => reject(new Error('Не удалось прочитать файл ' + file.name));
        reader.readAsDataURL(file);
    });
}

/**
 * Превью медиа в карточках постов: для постов со вложениями дотягивает их
 * список (post.get) и вставляет полосу миниатюр — фото картинкой,
 * видео первым кадром. Клик по миниатюре ведет на страницу поста.
 */
async function enhancePostCardsWithMedia(containerEl, posts) {
    const withFiles = (posts || []).filter((p) => Number(p.attachmentsCount) > 0);
    if (!withFiles.length) return;

    await Promise.all(withFiles.map(async (p) => {
        try {
            const res = await api('post.get', { postId: p.id });
            const atts = (res.attachments || [])
                .filter((a) => /^(image|video)\//.test(a.mimeType || ''))
                .slice(0, 4);

            if (!atts.length) return;

            const link = containerEl.querySelector(`article a[href="post.html?id=${p.id}"]`);
            const card = link && link.closest('article');

            if (!card || card.querySelector('.card-media')) return;

            const items = [];
            for (const a of atts) {
                try {
                    const url = await mediaSrc(a);
                    items.push(
                        `<a class="card-media-item" href="post.html?id=${p.id}" ` +
                        `title="${esc(a.fileName)}">` +
                        (/^video\//.test(a.mimeType)
                            ? `<video src="${url}" preload="metadata" muted></video><i>▶</i>`
                            : `<img loading="lazy" src="${url}" alt="${esc(a.fileName)}">`) +
                        '</a>');
                } catch (e) { /* отдельное вложение пропускаем */ }
            }

            if (!items.length) return;

            const strip = document.createElement('div');
            strip.className = 'card-media';
            strip.innerHTML = items.join('');

            const meta = card.querySelector('.meta-row');
            if (meta) meta.before(strip); else card.appendChild(strip);
        } catch (e) { /* превью некритично */ }
    }));
}

/** Последовательно грузит все выбранные файлы; возвращает список ошибок. */
async function uploadFilesSequentially(postId, files, onUploaded) {
    const failed = [];

    for (const f of files) {
        try {
            await uploadFileToPost(postId, f);
            if (onUploaded) onUploaded(f);
        } catch (e) {
            failed.push(f.name + ' — ' + e.message);
        }
    }

    return failed;
}
