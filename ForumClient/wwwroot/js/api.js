'use strict';

const JFC_PENDING = new Map();
let jfcSeq = 1;

(function () {
    const wv = window.chrome && window.chrome.webview;
    if (!wv) return;

    wv.addEventListener('message', (ev) => {
        const msg = ev.data;
        if (!msg || typeof msg.reqId !== 'number') return;

        const pending = JFC_PENDING.get(msg.reqId);
        if (!pending) return;

        JFC_PENDING.delete(msg.reqId);

        if (msg.ok) pending.resolve(msg.data);
        else pending.reject(new Error(msg.error || 'Ошибка запроса'));
    });
})();

function api(action, args = {}) {
    return new Promise((resolve, reject) => {
        const wv = window.chrome && window.chrome.webview;
        if (!wv) {
            reject(new Error('Страница открыта вне приложения ForumClient'));
            return;
        }

        const reqId = jfcSeq++;
        JFC_PENDING.set(reqId, { resolve, reject });

        setTimeout(() => {
            if (JFC_PENDING.has(reqId)) {
                JFC_PENDING.delete(reqId);
                reject(new Error('Приложение не ответило на запрос "' + action + '"'));
            }
        }, 20000);

        wv.postMessage({ reqId, action, args });
    });
}

window.addEventListener('error', (e) => {
    toast('Ошибка скрипта: ' + (e.message || 'неизвестная'), 'error');
});

window.addEventListener('unhandledrejection', (e) => {
    const reason = e.reason && e.reason.message ? e.reason.message : String(e.reason);
    toast('Ошибка: ' + reason, 'error');
});

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
 * Загружает один файл к посту прямым POST-запросом (тело = сырой файл,
 * без base64 и без лимитов моста). Перехватывается на стороне C#.
 */
async function uploadFileToPost(postId, file) {
    const resp = await fetch(
        '/upload/' + postId + '/' + encodeURIComponent(file.name),
        { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: file });

    if (resp.ok) return;

    let message = 'HTTP ' + resp.status;
    try {
        const j = await resp.json();
        if (j && j.error) message = j.error;
    } catch (e) { /* не JSON */ }

    throw new Error(message);
}

/**
 * Превью медиа в карточках постов: для постов со вложениями дотягивает
 * их список (post.get) и вставляет полосу миниатюр — фото картинкой,
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

            const strip = document.createElement('div');
            strip.className = 'card-media';
            strip.innerHTML = atts.map((a) =>
                `<a class="card-media-item" href="post.html?id=${p.id}" ` +
                `title="${esc(a.fileName)}">` +
                (/^video\//.test(a.mimeType)
                    ? `<video src="/media/${a.id}" preload="metadata" muted></video><i>▶</i>`
                    : `<img loading="lazy" src="/media/${a.id}" alt="${esc(a.fileName)}">`) +
                '</a>').join('');

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
