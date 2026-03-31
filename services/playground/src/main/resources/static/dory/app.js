document.addEventListener('DOMContentLoaded', () => {
  // Read owner from URL path: /dory/{owner}
  const owner = window.location.pathname.split('/').filter(Boolean)[1];

  const API_BASE = '/api/v1/dory/reminders';

  const intervalMap = {
    daily: 'DAILY',
    weekly: 'WEEKLY',
    monthly: 'MONTHLY',
    yearly: 'YEARLY'
  };
  const intervalDisplayMap = {
    DAILY: 'daily',
    WEEKLY: 'weekly',
    MONTHLY: 'monthly',
    YEARLY: 'yearly'
  };

  const form = document.getElementById('add-form');
  const titleInput = document.getElementById('title');
  const nextOccurrenceInput = document.getElementById('nextOccurrence');
  const typeRadios = document.getElementsByName('type');
  const advancedOptions = document.getElementById('advanced-options');
  const intervalSelect = document.getElementById('interval');
  const descriptionInput = document.getElementById('description');
  const remindersContainer = document.getElementById('reminders-container');
  const quickPicks = document.querySelectorAll('.chip');

  // --- 1. Date Picker Init ---

  function getNextHourRounding() {
    const now = new Date();
    now.setHours(now.getHours() + 1);
    now.setMinutes(0, 0, 0);
    return now;
  }

  const fp = flatpickr(nextOccurrenceInput, {
    enableTime: true,
    dateFormat: "Y-m-d h:i K",
    minuteIncrement: 5,
    defaultDate: getNextHourRounding(),
    disableMobile: "true"
  });

  // --- 2. UI Logic ---

  typeRadios.forEach(radio => {
    radio.addEventListener('change', (e) => {
      if (e.target.value === 'recurring') {
        advancedOptions.classList.remove('hidden');
      } else {
        advancedOptions.classList.add('hidden');
      }
    });
  });

  quickPicks.forEach(chip => {
    chip.addEventListener('click', (e) => {
      const offset = e.target.dataset.offset;
      const now = new Date();
      let targetDate = new Date();

      if (offset === '+1h') {
        targetDate.setHours(now.getHours() + 1);
      } else if (offset === 'tonight') {
        targetDate.setHours(20, 0, 0, 0);
        if (now.getHours() >= 20) targetDate.setDate(now.getDate() + 1);
      } else if (offset === 'tomorrow') {
        targetDate.setDate(now.getDate() + 1);
        targetDate.setHours(9, 0, 0, 0);
      }

      fp.setDate(targetDate);
      nextOccurrenceInput.style.borderColor = 'var(--primary-color)';
      setTimeout(() => { nextOccurrenceInput.style.borderColor = ''; }, 300);
    });
  });

  // --- 3. API Interactions ---

  async function fetchReminders() {
    try {
      const res = await fetch(`${API_BASE}?owner=${owner}&status=LIVE`);
      if (!res.ok) throw new Error('Failed to fetch');
      renderReminders(await res.json());
    } catch (error) {
      console.error(error);
      remindersContainer.innerHTML =
        `<div class="empty-state" style="color: var(--danger-color)">
           Error loading reminders
         </div>`;
    }
  }

  setInterval(fetchReminders, 30000);

  async function updateReminder(id, updates) {
    try {
      await fetch(`${API_BASE}/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(updates)
      });
      fetchReminders();
    } catch (error) {
      console.error('Failed to update reminder', error);
      alert('Failed to save changes');
    }
  }

  async function deleteReminder(id) {
    if (!confirm('Are you sure you want to delete this reminder?')) return;
    try {
      await fetch(`${API_BASE}/${id}`, { method: 'DELETE' });
      fetchReminders();
    } catch (error) {
      console.error(error);
    }
  }

  // --- 4. Rendering ---

  function renderReminders(reminders) {
    if (!reminders || reminders.length === 0) {
      remindersContainer.innerHTML = `
        <div class="empty-state">
          No active reminders. Add your first one above! 🐟
        </div>
      `;
      return;
    }

    reminders.sort((a, b) => new Date(a.nextOccurrence) - new Date(b.nextOccurrence));

    remindersContainer.innerHTML = '';
    const now = new Date();

    reminders.forEach(r => {
      const dateObj = new Date(r.nextOccurrence);
      const dateStr = dateObj.toLocaleString('en-US', {
        month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit'
      });

      const isRecurring = r.type === 'recurring';
      const typeBadge = isRecurring
        ? `<span class="badge recurring" title="Recurring">🔁 ${intervalDisplayMap[r.recurrenceType] || r.recurrenceType}</span>`
        : `<span class="badge" title="One-off">⚡️ one-off</span>`;

      const el = document.createElement('div');
      el.className = 'reminder-item';

      const msRemaining = dateObj.getTime() - now.getTime();
      const hoursRemaining = msRemaining / (1000 * 60 * 60);
      const isSnoozed = r.snoozeUntil && new Date(r.snoozeUntil) > now;
      let timeStatusHtml = `⏰ `;

      if (isSnoozed) {
        el.classList.add('snoozed');
        const snoozeObj = new Date(r.snoozeUntil);
        const snoozeStr = snoozeObj.toLocaleTimeString('en-US',
          { hour: 'numeric', minute: '2-digit' });
        timeStatusHtml +=
          `<span class="editable-time" data-id="${r.id}"
                 data-current="${r.nextOccurrence}">${dateStr}</span>
           <span style="color:var(--text-muted); margin-left:8px;">
             (Postponed to ${snoozeStr})
           </span>`;
      } else if (hoursRemaining < 0) {
        el.classList.add('urgency-overdue');
        timeStatusHtml +=
          `<span class="editable-time" data-id="${r.id}"
                 data-current="${r.nextOccurrence}">${dateStr}</span>
           <span style="color:var(--danger-color);font-weight:bold;margin-left:8px;">
             (Overdue!)
           </span>`;
      } else {
        timeStatusHtml +=
          `<span class="editable-time" data-id="${r.id}"
                 data-current="${r.nextOccurrence}">${dateStr}</span>`;
      }

      el.innerHTML = `
        <div class="reminder-content">
          <div class="reminder-title-edit" contenteditable="true"
               data-id="${r.id}" spellcheck="false"
               title="Click to edit title">${r.title}</div>
          <div class="reminder-meta">
            ${timeStatusHtml}
            ${typeBadge}
          </div>
          <div class="reminder-desc-edit" contenteditable="true"
               data-id="${r.id}" data-placeholder="Add notes..."
               spellcheck="false">${r.description || ''}</div>
        </div>
        <div class="reminder-actions">
          <button class="icon-btn delete" title="Delete">🗑</button>
        </div>
      `;

      el.querySelector('.delete').addEventListener('click', () => deleteReminder(r.id));

      const titleEdit = el.querySelector('.reminder-title-edit');
      const descEdit = el.querySelector('.reminder-desc-edit');

      const handleTextSave = (targetElement, fieldName) => {
        const newText = targetElement.textContent.trim();
        if (fieldName === 'title' && !newText) {
          targetElement.textContent = r.title;
          return;
        }
        if (newText !== (r[fieldName] || '')) {
          // Merge changed field into current reminder state for PUT
          updateReminder(r.id, { ...buildPutBody(r), [fieldName]: newText });
        }
      };

      [titleEdit, descEdit].forEach(el => {
        el.addEventListener('blur', (e) => handleTextSave(
          e.target,
          e.target.classList.contains('reminder-title-edit') ? 'title' : 'description'
        ));
        el.addEventListener('keydown', (e) => {
          if (e.key === 'Enter') { e.preventDefault(); e.target.blur(); }
        });
      });

      const timeEdit = el.querySelector('.editable-time');
      flatpickr(timeEdit, {
        enableTime: true,
        dateFormat: "Y-m-d h:i K",
        minuteIncrement: 5,
        defaultDate: new Date(r.nextOccurrence),
        disableMobile: "true",
        position: "auto center",
        onClose: (selectedDates) => {
          if (selectedDates.length > 0) {
            const newDateIso = selectedDates[0].toISOString();
            if (newDateIso !== r.nextOccurrence) {
              updateReminder(r.id, { ...buildPutBody(r), nextOccurrence: newDateIso });
            }
          }
        }
      });

      timeEdit.addEventListener('click', (e) => { e.preventDefault(); });

      remindersContainer.appendChild(el);
    });
  }

  // Build a complete ReminderRequest body from the current reminder state
  function buildPutBody(r) {
    return {
      title: r.title,
      type: r.type,
      recurrenceType: r.recurrenceType || null,
      nextOccurrence: r.nextOccurrence,
      snoozeUntil: r.snoozeUntil || null,
      description: r.description || null
    };
  }

  // --- 5. Form Submission ---

  form.addEventListener('submit', async (e) => {
    e.preventDefault();

    const title = titleInput.value.trim();
    const nextOccurrenceRaw = nextOccurrenceInput.value;
    const type = document.querySelector('input[name="type"]:checked').value;

    if (!title || !nextOccurrenceRaw) return;

    const nextOccurrence = new Date(nextOccurrenceRaw).toISOString();

    const newReminder = { title, type, nextOccurrence };

    if (type === 'recurring') {
      newReminder.recurrenceType = intervalMap[intervalSelect.value];
    }

    if (descriptionInput.value.trim()) {
      newReminder.description = descriptionInput.value.trim();
    }

    try {
      const res = await fetch(`${API_BASE}?owner=${owner}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(newReminder)
      });

      if (!res.ok) throw new Error('Failed to create');

      titleInput.value = '';
      descriptionInput.value = '';
      titleInput.focus();
      fp.setDate(getNextHourRounding());

      fetchReminders();
    } catch (error) {
      console.error(error);
      alert('Failed to save reminder');
    }
  });

  // Init
  fetchReminders();
});
