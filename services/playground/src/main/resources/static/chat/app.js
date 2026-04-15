const API = '/api/v1';

// ── State ─────────────────────────────────────────────────────────────────────
const state = {
  userId: null,
  userName: null,
  users: {},          // id → { id, name }
  channels: [],       // ChannelDTO[]
  dms: [],            // ChannelDTO[]
  activeChannelId: null,
  lastRenderedMessageId: null,
  ws: null
};

// ── Auth guard ────────────────────────────────────────────────────────────────
state.userId = localStorage.getItem('chatUserId');
state.userName = localStorage.getItem('chatUserName');
if (!state.userId) {
  window.location.href = '/chat/login';
}

// ── API helper (attaches X-Mock-User-Id to every request) ────────────────────
async function api(path, options = {}) {
  const res = await fetch(`${API}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      'X-Mock-User-Id': state.userId,
      ...(options.headers || {})
    }
  });
  if (res.status === 204) return null;
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.message || `HTTP ${res.status}`);
  }
  return res.json();
}

// ── Utilities ─────────────────────────────────────────────────────────────────

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

// Extract ms timestamp from UUID v7 high 48 bits
function uuidToMs(uuid) {
  return parseInt(uuid.replace(/-/g, '').slice(0, 12), 16);
}

function formatTime(uuid) {
  return new Date(uuidToMs(uuid)).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function formatDay(uuid) {
  return new Date(uuidToMs(uuid)).toLocaleDateString([], {
    weekday: 'long', month: 'long', day: 'numeric'
  });
}

function resolvedName(id) {
  return state.users[id]?.name || id.slice(0, 8) + '…';
}

// ── User cache ────────────────────────────────────────────────────────────────
function cacheUsers(users) {
  (users || []).forEach(u => { state.users[u.id] = u; });
}

async function resolveUsers(ids) {
  const missing = [...new Set(ids)].filter(id => !state.users[id]);
  if (!missing.length) return;
  const fetched = await api('/chat/users/batch', {
    method: 'POST',
    body: JSON.stringify(missing)
  });
  cacheUsers(fetched);
}

// ── Sidebar ───────────────────────────────────────────────────────────────────
function renderSidebar() {
  document.getElementById('current-user-name').textContent = state.userName || '';
  const av = document.getElementById('current-user-avatar');
  av.style.background = avatarColor(state.userId);
  av.textContent = initials(state.userName);

  renderNavList('channel-list', state.channels, c => ({
    prefix: '#',
    label: c.name || 'unnamed',
    unread: c.unread
  }));

  renderNavList('dm-list', state.dms, c => ({
    prefix: '@',
    label: state.users[c.otherUserId]?.name || 'Unknown',
    unread: c.unread
  }));
}

function renderNavList(listId, items, labelFn) {
  const ul = document.getElementById(listId);
  ul.innerHTML = items.map(c => {
    const { prefix, label, unread } = labelFn(c);
    const active = c.id === state.activeChannelId;
    return `
      <li>
        <button data-id="${c.id}"
                class="${active ? 'active' : ''} ${unread && !active ? 'unread' : ''}">
          <span>${prefix}</span>
          <span>${escapeHtml(label)}</span>
          ${unread && !active ? '<span class="unread-dot"></span>' : ''}
        </button>
      </li>`;
  }).join('');
  ul.querySelectorAll('button').forEach(btn => {
    btn.addEventListener('click', () => selectChannel(btn.dataset.id));
  });
}

// ── Messages ──────────────────────────────────────────────────────────────────
function msgHtml(msg) {
  const name = resolvedName(msg.senderId);
  const color = avatarColor(msg.senderId);
  const body = msg.deleted
    ? '<em>This message was deleted.</em>'
    : escapeHtml(msg.body || '');
  return `
    <div class="message-group" data-id="${msg.messageId}">
      <div class="avatar msg-avatar" style="background:${color}">${initials(name)}</div>
      <div class="msg-content">
        <div class="msg-header">
          <span class="msg-sender">${escapeHtml(name)}</span>
          <span class="msg-time">${formatTime(msg.messageId)}</span>
        </div>
        <div class="msg-body${msg.deleted ? ' deleted' : ''}">${body}</div>
      </div>
    </div>`;
}

function renderMessages(messages) {
  const list = document.getElementById('message-list');
  if (!messages.length) {
    list.innerHTML = '<div class="no-messages" style="padding:32px;color:#9b9c9e;text-align:center">No messages yet — say hello!</div>';
    return;
  }
  let html = '';
  let lastDay = null;
  for (const msg of messages) {
    const day = formatDay(msg.messageId);
    if (day !== lastDay) {
      html += `<div class="day-divider"><span>${day}</span></div>`;
      lastDay = day;
    }
    html += msgHtml(msg);
  }
  list.innerHTML = html;
  list.scrollTop = list.scrollHeight;
  state.lastRenderedMessageId = messages[messages.length - 1].messageId;
}

function appendMessage(msg) {
  const list = document.getElementById('message-list');
  // Remove "no messages" placeholder if present
  const placeholder = list.querySelector('.no-messages');
  if (placeholder) placeholder.remove();
  list.insertAdjacentHTML('beforeend', msgHtml(msg));
  list.scrollTop = list.scrollHeight;
  state.lastRenderedMessageId = msg.messageId;
}

function patchMessage(msg) {
  const el = document.querySelector(`.message-group[data-id="${msg.messageId}"]`);
  if (!el) return;
  const body = el.querySelector('.msg-body');
  if (msg.deleted) {
    body.innerHTML = '<em>This message was deleted.</em>';
    body.classList.add('deleted');
  } else if (msg.body != null) {
    body.textContent = msg.body;
    body.classList.remove('deleted');
  }
}

// ── Read position ─────────────────────────────────────────────────────────────
function markCurrentChannelRead() {
  if (state.activeChannelId && state.lastRenderedMessageId) {
    const url = `${API}/chat/channels/${state.activeChannelId}/read?lastReadMessageId=${state.lastRenderedMessageId}`;
    fetch(url, {
      method: 'POST',
      keepalive: true,
      headers: { 'X-Mock-User-Id': state.userId }
    }).catch(() => {});
  }
}

// ── Channel selection ─────────────────────────────────────────────────────────
async function selectChannel(channelId) {
  markCurrentChannelRead();
  state.activeChannelId = channelId;
  state.lastRenderedMessageId = null;
  const channel = [...state.channels, ...state.dms].find(c => c.id === channelId);
  const isDm = channel?.type === 'DM';

  // Mark as read in local state
  if (channel) channel.unread = false;

  document.getElementById('empty-state').classList.add('hidden');
  document.getElementById('channel-view').classList.remove('hidden');

  const displayName = isDm
    ? (state.users[channel?.otherUserId]?.name || 'Unknown')
    : (channel?.name || 'unknown');

  document.getElementById('channel-header-prefix').textContent = isDm ? '@' : '#';
  document.getElementById('channel-header-name').textContent = displayName;
  document.getElementById('message-input').placeholder = `Message ${isDm ? '@' : '#'}${displayName}`;

  renderSidebar();

  const messages = await api(`/chat/channels/${channelId}/messages?limit=50`);
  await resolveUsers(messages.map(m => m.senderId));
  renderMessages(messages);
}

// ── Load channels ─────────────────────────────────────────────────────────────
async function loadChannels() {
  const data = await api('/chat/channels');
  state.channels = data.channels || [];
  state.dms = data.dms || [];
  cacheUsers(data.users || []);
  // Seed cache with current user so own messages resolve immediately
  state.users[state.userId] = { id: state.userId, name: state.userName };
  renderSidebar();
}

// ── WebSocket ─────────────────────────────────────────────────────────────────
function connectWebSocket() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const ws = new WebSocket(`${proto}//${location.host}/ws?userId=${state.userId}`);
  state.ws = ws;

  ws.onopen = () => console.log('[WS] connected');

  ws.onclose = () => {
    console.log('[WS] disconnected — reconnecting in 3 s');
    setTimeout(connectWebSocket, 3000);
  };

  ws.onerror = err => console.error('[WS] error', err);

  ws.onmessage = async e => {
    const { type, message, channel } = JSON.parse(e.data);
    const payload = message || channel;
    const isActive = payload?.channelId === state.activeChannelId;

    if (type === 'MESSAGE_CREATED') {
      const ch = [...state.channels, ...state.dms].find(c => c.id === payload.channelId);
      if (!ch) {
        // Unknown channel — e.g. first-ever DM from someone. Reload sidebar to pick it up.
        await loadChannels();
      } else if (!isActive) {
        ch.unread = true;
      }

      if (isActive) {
        // Avoid duplicate if sender already appended from REST response
        if (!document.querySelector(`.message-group[data-id="${payload.messageId}"]`)) {
          await resolveUsers([payload.senderId]);
          appendMessage(payload);
        }
      }
      renderSidebar();

    } else if (type === 'MESSAGE_EDITED' || type === 'MESSAGE_DELETED') {
      if (isActive) patchMessage(payload);

    } else if (type === 'CHANNEL_JOINED') {
      // Server confirmed subscription — refresh sidebar so new channel appears.
      await loadChannels();

    } else if (type === 'CHANNEL_LEFT') {
      // Remove from local state and re-render sidebar.
      state.channels = state.channels.filter(c => c.id !== payload.id);
      if (state.activeChannelId === payload.id) {
        state.activeChannelId = null;
        document.getElementById('channel-view').classList.add('hidden');
        document.getElementById('empty-state').classList.remove('hidden');
      }
      renderSidebar();
    }
  };
}

// ── Send message ──────────────────────────────────────────────────────────────
document.getElementById('message-form').addEventListener('submit', async e => {
  e.preventDefault();
  const input = document.getElementById('message-input');
  const body = input.value.trim();
  if (!body || !state.activeChannelId) return;
  input.value = '';

  try {
    const msg = await api(`/chat/channels/${state.activeChannelId}/messages`, {
      method: 'POST',
      body: JSON.stringify({ body })
    });
    // Append from REST response — the sender's WS push goes to the channel topic
    // which the handler echoes back, but we deduplicate by messageId above.
    // For DMs the server only pushes to the recipient, so append here is the
    // only way the sender sees their own message immediately.
    appendMessage(msg);
  } catch (err) {
    console.error('Send failed:', err);
    input.value = body;
  }
});

// ── Browse channels modal ─────────────────────────────────────────────────────
const browseChannelModal = document.getElementById('browse-channel-modal');

document.getElementById('browse-channel-btn').addEventListener('click', async () => {
  browseChannelModal.classList.remove('hidden');
  const listEl = document.getElementById('browse-channel-list');
  listEl.innerHTML = '<div style="padding:8px;color:#9b9c9e">Loading…</div>';

  const channels = await api('/chat/channels/browse');
  if (!channels.length) {
    listEl.innerHTML = '<div style="padding:8px;color:#9b9c9e">No public channels yet.</div>';
    return;
  }
  listEl.innerHTML = channels.map(c => `
    <div style="display:flex;align-items:center;justify-content:space-between;
                padding:8px 4px;border-bottom:1px solid #f0f0f0">
      <span style="font-size:15px"><span style="color:#9b9c9e">#</span> ${escapeHtml(c.name)}</span>
      ${c.joined
        ? '<span style="font-size:13px;color:#9b9c9e">Joined</span>'
        : `<button data-id="${c.id}" class="join-btn"
             style="padding:4px 12px;border-radius:6px;border:none;
                    background:#4a9eff;color:#fff;font-size:13px;cursor:pointer">
             Join
           </button>`
      }
    </div>
  `).join('');

  listEl.querySelectorAll('.join-btn').forEach(btn => {
    btn.addEventListener('click', async () => {
      btn.disabled = true;
      btn.textContent = '…';
      await api(`/chat/channels/${btn.dataset.id}/join`, { method: 'POST' });
      btn.textContent = 'Joined';
      btn.style.background = 'none';
      btn.style.color = '#9b9c9e';
      btn.style.cursor = 'default';
      await loadChannels();
    });
  });
});

document.getElementById('cancel-browse-btn').addEventListener('click', () => {
  browseChannelModal.classList.add('hidden');
});

// ── New channel modal ─────────────────────────────────────────────────────────
const newChannelModal = document.getElementById('new-channel-modal');
document.getElementById('new-channel-btn').addEventListener('click', () => {
  newChannelModal.classList.remove('hidden');
  document.getElementById('channel-name-input').focus();
});
document.getElementById('cancel-channel-btn').addEventListener('click', () => {
  newChannelModal.classList.add('hidden');
});
document.getElementById('new-channel-form').addEventListener('submit', async e => {
  e.preventDefault();
  const name = document.getElementById('channel-name-input').value.trim();
  if (!name) return;
  await api('/chat/channels', { method: 'POST', body: JSON.stringify({ name, type: 'PUBLIC' }) });
  newChannelModal.classList.add('hidden');
  document.getElementById('channel-name-input').value = '';
  await loadChannels();
});

// ── New DM modal ──────────────────────────────────────────────────────────────
const newDmModal = document.getElementById('new-dm-modal');
document.getElementById('new-dm-btn').addEventListener('click', async () => {
  newDmModal.classList.remove('hidden');
  const listEl = document.getElementById('dm-user-list');
  listEl.innerHTML = '<div style="padding:8px;color:#9b9c9e">Loading…</div>';

  const users = await api('/chat/users');
  const others = users.filter(u => u.id !== state.userId);
  if (!others.length) {
    listEl.innerHTML = '<div style="padding:8px;color:#9b9c9e">No other users found.</div>';
    return;
  }
  listEl.innerHTML = others.map(u => `
    <button data-id="${u.id}" style="
      width:100%;text-align:left;padding:8px 10px;border:none;background:none;
      border-radius:6px;cursor:pointer;font-size:15px;display:flex;align-items:center;gap:10px;">
      <div class="avatar" style="background:${avatarColor(u.id)};width:32px;height:32px;font-size:12px;border-radius:50%">
        ${initials(u.name)}
      </div>
      ${escapeHtml(u.name || 'Unknown')}
    </button>
  `).join('');
  listEl.querySelectorAll('button').forEach(btn => {
    btn.addEventListener('mouseover', () => btn.style.background = '#f5f5f5');
    btn.addEventListener('mouseout', () => btn.style.background = 'none');
    btn.addEventListener('click', async () => {
      newDmModal.classList.add('hidden');
      const channel = await api(`/chat/dms?recipientId=${btn.dataset.id}`, { method: 'POST' });
      await loadChannels();
      selectChannel(channel.id);
    });
  });
});
document.getElementById('cancel-dm-btn').addEventListener('click', () => {
  newDmModal.classList.add('hidden');
});

// ── Sign out ──────────────────────────────────────────────────────────────────
document.getElementById('sign-out-btn').addEventListener('click', () => {
  if (state.ws) state.ws.close();
  localStorage.removeItem('chatUserId');
  localStorage.removeItem('chatUserName');
  window.location.href = '/chat/login';
});

// ── Init ──────────────────────────────────────────────────────────────────────
async function init() {
  await loadChannels();
  connectWebSocket();
}

window.addEventListener('beforeunload', markCurrentChannelRead);

init();
