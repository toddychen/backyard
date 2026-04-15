const API = '/api/v1';


function signIn(userId, userName) {
  localStorage.setItem('chatUserId', userId);
  localStorage.setItem('chatUserName', userName);
  window.location.href = '/chat/app';
}

// ── Load user list ────────────────────────────────────────────────────────────
async function loadUsers() {
  const grid = document.getElementById('user-grid');
  try {
    const res = await fetch(`${API}/chat/users`);
    const users = await res.json();

    if (users.length === 0) {
      grid.innerHTML = '<div class="grid-status">No users yet — create one below.</div>';
      return;
    }

    grid.innerHTML = users.map(u => `
      <button class="user-card" data-id="${u.id}" data-name="${u.name || 'Unknown'}">
        <div class="avatar" style="background:${avatarColor(u.id)}">${initials(u.name)}</div>
        <span>${u.name || 'Unknown'}</span>
      </button>
    `).join('');

    grid.querySelectorAll('.user-card').forEach(btn => {
      btn.addEventListener('click', () => signIn(btn.dataset.id, btn.dataset.name));
    });
  } catch (e) {
    grid.innerHTML = '<div class="grid-status">Failed to load users.</div>';
  }
}

// ── Create user form ──────────────────────────────────────────────────────────
document.getElementById('create-form').addEventListener('submit', async e => {
  e.preventDefault();
  const name = document.getElementById('new-name').value.trim();
  const errEl = document.getElementById('create-error');
  errEl.classList.add('hidden');

  try {
    const res = await fetch(`${API}/chat/users`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name })
    });

    if (res.ok) {
      const user = await res.json();
      signIn(user.id, user.name);
    } else {
      const err = await res.json().catch(() => ({}));
      errEl.textContent = err.message || 'Failed to create user.';
      errEl.classList.remove('hidden');
    }
  } catch (e) {
    errEl.textContent = 'Network error — is the server running?';
    errEl.classList.remove('hidden');
  }
});

// ── Init ─────────────────────────────────────────────────────────────────────
loadUsers();
