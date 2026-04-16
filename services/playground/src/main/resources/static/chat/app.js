const API = '/api/v1';

// ── State ─────────────────────────────────────────────────────────────────────
const state = {
  userId: null,
  userName: null,
  users: {},              // id → { id, name }
  channels: [],           // ChannelDTO[]
  dms: [],                // ChannelDTO[]
  activeChannelId: null,
  lastRenderedMessageId: null,
  messagesById: {},       // messageId → MessageDTO (for thread parent lookup)
  activeThreadParentId: null,
  pendingDeleteId: null,          // message delete
  pendingReplyDeleteId: null,     // reply delete
  pendingReplyDeleteParentId: null,
  ws: null,
  socketId: null
};

// ── Auth guard ────────────────────────────────────────────────────────────────
state.userId = sessionStorage.getItem('chatUserId');
state.userName = sessionStorage.getItem('chatUserName');
if (!state.userId) {
  window.location.href = '/chat/login';
}

// Unique ID for this socket connection — scoped to this tab, generated once.
// Used to skip pushing events back to the originating socket while still
// delivering to other sockets of the same user (e.g. a second tab).
state.socketId = crypto.randomUUID();

// ── API helper (attaches X-Mock-User-Id and X-Socket-Id to every request) ────
async function api(path, options = {}) {
  const res = await fetch(`${API}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      'X-Mock-User-Id': state.userId,
      'X-Socket-Id': state.socketId,
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
  const isOwn = msg.senderId === state.userId;
  const body = msg.deleted
    ? '<em>This message was deleted.</em>'
    : escapeHtml(msg.body || '');
  const ownBtns = isOwn && !msg.deleted
    ? '<button class="msg-action-btn edit-btn" title="Edit">✏️</button>' +
      '<button class="msg-action-btn delete-btn" title="Delete">🗑️</button>'
    : '';
  const actions = !msg.deleted ? `
    <div class="msg-actions">
      ${ownBtns}
      <button class="msg-action-btn reply-btn" title="Reply in thread">💬</button>
    </div>` : '';
  // data-body stores the raw body for restoring on edit cancel
  const bodyAttr = !msg.deleted ? ` data-body="${escapeHtml(msg.body || '')}"` : '';
  const threadIndicator = msg.hasThread
    ? '<button class="thread-indicator">💬 View thread</button>' : '';
  return `
    <div class="message-group" data-id="${msg.messageId}"${bodyAttr}>
      ${actions}
      <div class="avatar msg-avatar" style="background:${color}">${initials(name)}</div>
      <div class="msg-content">
        <div class="msg-header">
          <span class="msg-sender">${escapeHtml(name)}</span>
          <span class="msg-time">${formatTime(msg.messageId)}</span>
        </div>
        <div class="msg-body${msg.deleted ? ' deleted' : ''}">${body}</div>
        ${threadIndicator}
      </div>
    </div>`;
}

function renderMessages(messages) {
  state.messagesById = {};
  const list = document.getElementById('message-list');
  if (!messages.length) {
    list.innerHTML = '<div class="no-messages" style="padding:32px;color:#9b9c9e;text-align:center">No messages yet — say hello!</div>';
    return;
  }
  let html = '';
  let lastDay = null;
  for (const msg of messages) {
    state.messagesById[msg.messageId] = msg;
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
  state.messagesById[msg.messageId] = msg;
  const list = document.getElementById('message-list');
  // Remove "no messages" placeholder if present
  const placeholder = list.querySelector('.no-messages');
  if (placeholder) placeholder.remove();
  list.insertAdjacentHTML('beforeend', msgHtml(msg));
  list.scrollTop = list.scrollHeight;
  state.lastRenderedMessageId = msg.messageId;
}

// Apply an edit from a WS MESSAGE_EDITED event (or directly after REST PATCH)
function applyEdit(msg) {
  const el = document.querySelector(`.message-group[data-id="${msg.messageId}"]`);
  if (!el) return;
  cancelEdit(el); // no-op if not editing
  const body = el.querySelector('.msg-body');
  body.textContent = msg.body;
  body.classList.remove('deleted');
  el.dataset.body = msg.body;
  if (state.messagesById[msg.messageId]) state.messagesById[msg.messageId].body = msg.body;
}

// Apply a delete: tombstone if has_thread, otherwise animate-and-remove
function applyDelete(msg) {
  const el = document.querySelector(`.message-group[data-id="${msg.messageId}"]`);
  if (!el) return;
  if (state.messagesById[msg.messageId]) state.messagesById[msg.messageId].deleted = true;
  if (msg.hasThread) {
    const body = el.querySelector('.msg-body');
    body.innerHTML = '<em>This message was deleted.</em>';
    body.classList.add('deleted');
    el.querySelector('.msg-actions')?.remove();
    delete el.dataset.body;
  } else {
    el.classList.add('msg-deleting');
    setTimeout(() => {
      el.style.maxHeight = el.offsetHeight + 'px';
      el.style.overflow = 'hidden';
      el.style.transition = 'max-height 0.2s ease-out, opacity 0.2s ease-out, margin-bottom 0.2s ease-out';
      requestAnimationFrame(() => {
        el.style.maxHeight = '0';
        el.style.opacity = '0';
        el.style.marginBottom = '0';
      });
      el.addEventListener('transitionend', () => el.remove(), { once: true });
    }, 350);
  }
}

// ── Edit flow ─────────────────────────────────────────────────────────────────
function startEdit(groupEl) {
  if (groupEl.querySelector('.msg-edit-area')) return; // already editing
  const originalBody = groupEl.dataset.body || '';
  const bodyEl = groupEl.querySelector('.msg-body');
  bodyEl.innerHTML = `
    <div class="msg-edit-area">
      <textarea class="edit-textarea" maxlength="4000">${escapeHtml(originalBody)}</textarea>
      <div class="msg-edit-btns">
        <button class="msg-edit-save">Save</button>
        <button class="msg-edit-cancel">Cancel</button>
      </div>
    </div>`;
  const ta = bodyEl.querySelector('.edit-textarea');
  ta.focus();
  ta.setSelectionRange(ta.value.length, ta.value.length);
}

function cancelEdit(groupEl) {
  const bodyEl = groupEl.querySelector('.msg-body');
  if (!bodyEl?.querySelector('.msg-edit-area')) return;
  const originalBody = groupEl.dataset.body || '';
  bodyEl.innerHTML = escapeHtml(originalBody);
  bodyEl.classList.remove('deleted');
}

async function submitEdit(groupEl) {
  const ta = groupEl.querySelector('.edit-textarea');
  const newBody = ta.value.trim();
  if (!newBody) return;
  const messageId = groupEl.dataset.id;
  try {
    const dto = await api(`/chat/channels/${state.activeChannelId}/messages/${messageId}`, {
      method: 'PATCH',
      body: JSON.stringify({ body: newBody })
    });
    applyEdit(dto);
  } catch (err) {
    console.error('Edit failed:', err);
    cancelEdit(groupEl);
  }
}

// ── Message list click delegation ────────────────────────────────────────────
document.getElementById('message-list').addEventListener('click', e => {
  const group = e.target.closest('.message-group');
  if (!group) return;
  if (e.target.closest('.edit-btn')) {
    startEdit(group);
  } else if (e.target.closest('.delete-btn')) {
    state.pendingDeleteId = group.dataset.id;
    document.getElementById('delete-confirm-modal').classList.remove('hidden');
  } else if (e.target.closest('.msg-edit-save')) {
    submitEdit(group);
  } else if (e.target.closest('.msg-edit-cancel')) {
    cancelEdit(group);
  } else if (e.target.closest('.reply-btn') || e.target.closest('.thread-indicator')) {
    const msg = state.messagesById[group.dataset.id];
    if (msg) openThread(msg);
  }
});

document.getElementById('cancel-delete-btn').addEventListener('click', () => {
  state.pendingDeleteId = null;
  state.pendingReplyDeleteId = null;
  state.pendingReplyDeleteParentId = null;
  document.getElementById('delete-confirm-modal').classList.add('hidden');
});

document.getElementById('confirm-delete-btn').addEventListener('click', async () => {
  document.getElementById('delete-confirm-modal').classList.add('hidden');

  if (state.pendingReplyDeleteId) {
    const replyId = state.pendingReplyDeleteId;
    const parentId = state.pendingReplyDeleteParentId;
    state.pendingReplyDeleteId = null;
    state.pendingReplyDeleteParentId = null;
    try {
      const dto = await api(`/chat/messages/${parentId}/replies/${replyId}`, { method: 'DELETE' });
      applyReplyDelete(dto);
    } catch (err) {
      console.error('Reply delete failed:', err);
    }
    return;
  }

  const messageId = state.pendingDeleteId;
  state.pendingDeleteId = null;
  if (!messageId) return;
  try {
    const dto = await api(`/chat/channels/${state.activeChannelId}/messages/${messageId}`, {
      method: 'DELETE'
    });
    applyDelete(dto);
  } catch (err) {
    console.error('Delete failed:', err);
  }
});

// ── Thread panel ──────────────────────────────────────────────────────────────
function replyHtml(reply) {
  const name = resolvedName(reply.senderId);
  const color = avatarColor(reply.senderId);
  const isOwn = reply.senderId === state.userId;
  const body = reply.deleted
    ? '<em>This reply was deleted.</em>'
    : escapeHtml(reply.body || '');
  const ownBtns = isOwn && !reply.deleted
    ? '<button class="msg-action-btn edit-btn" title="Edit">✏️</button>' +
      '<button class="msg-action-btn delete-btn" title="Delete">🗑️</button>'
    : '';
  const actions = ownBtns ? `<div class="msg-actions">${ownBtns}</div>` : '';
  const bodyAttr = !reply.deleted ? ` data-body="${escapeHtml(reply.body || '')}"` : '';
  return `
    <div class="message-group" data-id="${reply.messageId}" data-parent-id="${reply.parentId}"${bodyAttr}>
      ${actions}
      <div class="avatar msg-avatar" style="background:${color}">${initials(name)}</div>
      <div class="msg-content">
        <div class="msg-header">
          <span class="msg-sender">${escapeHtml(name)}</span>
          <span class="msg-time">${formatTime(reply.messageId)}</span>
          ${reply.edited ? '<span class="msg-edited">(edited)</span>' : ''}
        </div>
        <div class="msg-body${reply.deleted ? ' deleted' : ''}">${body}</div>
      </div>
    </div>`;
}

function openThread(msg) {
  state.activeThreadParentId = msg.messageId;

  const parentEl = document.getElementById('thread-parent');
  parentEl.innerHTML = msgHtml(msg);
  parentEl.querySelector('.msg-actions')?.remove();
  parentEl.querySelector('.thread-indicator')?.remove();

  document.getElementById('thread-reply-list').innerHTML =
    '<div style="padding:16px;color:#9b9c9e;text-align:center;font-size:13px">Loading…</div>';

  // Case 1: if at bottom, snap back to bottom after reflow so the last message
  // stays pinned. Case 2: do nothing — browser overflow-anchor keeps the top stable.
  const list = document.getElementById('message-list');
  const wasAtBottom = list.scrollTop + list.clientHeight >= list.scrollHeight - 8;

  document.getElementById('thread-panel').classList.remove('hidden');
  document.getElementById('reply-input').focus();

  if (wasAtBottom) {
    requestAnimationFrame(() => { list.scrollTop = list.scrollHeight; });
  }

  loadReplies();
}

function closeThread() {
  const list = document.getElementById('message-list');
  const wasAtBottom = list.scrollTop + list.clientHeight >= list.scrollHeight - 8;

  state.activeThreadParentId = null;
  document.getElementById('thread-panel').classList.add('hidden');
  document.getElementById('thread-reply-list').innerHTML = '';
  document.getElementById('thread-parent').innerHTML = '';

  if (wasAtBottom) {
    requestAnimationFrame(() => { list.scrollTop = list.scrollHeight; });
  }
}

async function loadReplies() {
  const parentId = state.activeThreadParentId;
  if (!parentId) return;
  const replies = await api(
    `/chat/messages/${parentId}/replies?parentChannelId=${state.activeChannelId}&limit=50`
  );
  if (state.activeThreadParentId !== parentId) return; // thread switched while loading
  await resolveUsers(replies.map(r => r.senderId));
  const list = document.getElementById('thread-reply-list');
  if (!replies.length) {
    list.innerHTML = '<div style="padding:16px;color:#9b9c9e;text-align:center;font-size:13px">No replies yet.</div>';
    return;
  }
  list.innerHTML = replies.map(replyHtml).join('');
  list.scrollTop = list.scrollHeight;
}

function appendReply(reply) {
  const list = document.getElementById('thread-reply-list');
  const placeholder = list.querySelector('div');
  if (placeholder && !placeholder.classList.contains('message-group')) placeholder.remove();
  list.insertAdjacentHTML('beforeend', replyHtml(reply));
  list.scrollTop = list.scrollHeight;

  // Ensure the parent message in the channel feed shows a thread indicator
  showThreadIndicator(reply.parentId);
}

/** Add a "View thread" button to a channel-feed message if not already present. */
function showThreadIndicator(messageId) {
  const el = document.querySelector(`#message-list .message-group[data-id="${messageId}"]`);
  if (!el) return;
  if (!el.querySelector('.thread-indicator')) {
    el.querySelector('.msg-content')
      ?.insertAdjacentHTML('beforeend', '<button class="thread-indicator">💬 View thread</button>');
  }
  if (state.messagesById[messageId]) state.messagesById[messageId].hasThread = true;
}

function applyReplyEdit(reply) {
  const el = document.querySelector(`#thread-reply-list .message-group[data-id="${reply.messageId}"]`);
  if (!el) return;
  cancelEdit(el);
  const body = el.querySelector('.msg-body');
  body.textContent = reply.body;
  body.classList.remove('deleted');
  el.dataset.body = reply.body;
  if (!el.querySelector('.msg-edited')) {
    el.querySelector('.msg-time')
      ?.insertAdjacentHTML('afterend', '<span class="msg-edited">(edited)</span>');
  }
}

function applyReplyDelete(reply) {
  const el = document.querySelector(`#thread-reply-list .message-group[data-id="${reply.messageId}"]`);
  if (!el) return;
  // Replies can't have sub-threads — animate and remove
  el.classList.add('msg-deleting');
  setTimeout(() => {
    el.style.maxHeight = el.offsetHeight + 'px';
    el.style.overflow = 'hidden';
    el.style.transition = 'max-height 0.2s ease-out, opacity 0.2s ease-out, margin-bottom 0.2s ease-out';
    requestAnimationFrame(() => {
      el.style.maxHeight = '0';
      el.style.opacity = '0';
      el.style.marginBottom = '0';
    });
    el.addEventListener('transitionend', () => el.remove(), { once: true });
  }, 350);
}

// Thread reply edit flow (reuses cancelEdit / startEdit from message edit flow)
async function submitReplyEdit(groupEl) {
  const ta = groupEl.querySelector('.edit-textarea');
  const newBody = ta.value.trim();
  if (!newBody) return;
  const replyId = groupEl.dataset.id;
  const parentId = groupEl.dataset.parentId;
  try {
    const dto = await api(`/chat/messages/${parentId}/replies/${replyId}`, {
      method: 'PATCH',
      body: JSON.stringify({ body: newBody })
    });
    applyReplyEdit(dto);
  } catch (err) {
    console.error('Reply edit failed:', err);
    cancelEdit(groupEl);
  }
}

// Thread reply list click delegation
document.getElementById('thread-reply-list').addEventListener('click', e => {
  const group = e.target.closest('.message-group');
  if (!group) return;
  if (e.target.closest('.edit-btn')) {
    startEdit(group);
  } else if (e.target.closest('.delete-btn')) {
    state.pendingReplyDeleteId = group.dataset.id;
    state.pendingReplyDeleteParentId = group.dataset.parentId;
    document.getElementById('delete-confirm-modal').classList.remove('hidden');
  } else if (e.target.closest('.msg-edit-save')) {
    submitReplyEdit(group);
  } else if (e.target.closest('.msg-edit-cancel')) {
    cancelEdit(group);
  }
});

document.getElementById('close-thread-btn').addEventListener('click', closeThread);

document.getElementById('reply-form').addEventListener('submit', async e => {
  e.preventDefault();
  const input = document.getElementById('reply-input');
  const body = input.value.trim();
  if (!body || !state.activeThreadParentId) return;
  input.value = '';
  try {
    const dto = await api(
      `/chat/messages/${state.activeThreadParentId}/replies?parentChannelId=${state.activeChannelId}`,
      { method: 'POST', body: JSON.stringify({ body }) }
    );
    appendReply(dto);
  } catch (err) {
    console.error('Reply send failed:', err);
    input.value = body;
  }
});

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
  closeThread();
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
  const ws = new WebSocket(`${proto}//${location.host}/ws?userId=${state.userId}&socketId=${state.socketId}`);
  state.ws = ws;

  ws.onopen = () => console.log('[WS] connected');

  ws.onclose = () => {
    console.log('[WS] disconnected — reconnecting in 3 s');
    setTimeout(connectWebSocket, 3000);
  };

  ws.onerror = err => console.error('[WS] error', err);

  ws.onmessage = async e => {
    const { type, message, reply, channel } = JSON.parse(e.data);
    const payload = message || reply || channel;
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

    } else if (type === 'MESSAGE_EDITED') {
      if (isActive) applyEdit(payload);
    } else if (type === 'MESSAGE_DELETED') {
      if (isActive) applyDelete(payload);

    } else if (type === 'REPLY_CREATED') {
      if (isActive) {
        // Update thread indicator on the parent message in the channel feed
        showThreadIndicator(payload.parentId);
        // Append to thread panel if it's open for this parent
        if (state.activeThreadParentId === payload.parentId) {
          // Avoid duplicate if sender already appended from REST response
          if (!document.querySelector(`#thread-reply-list .message-group[data-id="${payload.messageId}"]`)) {
            await resolveUsers([payload.senderId]);
            appendReply(payload);
          }
        }
      }
    } else if (type === 'REPLY_EDITED') {
      if (isActive && state.activeThreadParentId === payload.parentId) applyReplyEdit(payload);
    } else if (type === 'REPLY_DELETED') {
      if (isActive && state.activeThreadParentId === payload.parentId) applyReplyDelete(payload);

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
  sessionStorage.removeItem('chatUserId');
  sessionStorage.removeItem('chatUserName');
  window.location.href = '/chat/login';
});

// ── Init ──────────────────────────────────────────────────────────────────────
async function init() {
  await loadChannels();
  connectWebSocket();
}

window.addEventListener('beforeunload', markCurrentChannelRead);

init();
