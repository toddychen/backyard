function formatM(n) {
  return (n / 1_000_000).toFixed(3) + ' M';
}

function formatComma(n) {
  return n.toLocaleString('en-US');
}

function getLatestStats(messages) {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.credits !== undefined) {
      return { credits: m.credits, listCount: m.list_count, bought24h: m.bought_24h, bought1h: m.bought_1h };
    }
  }
  return null;
}

function getLatestSold(messages) {
  for (let i = messages.length - 1; i >= 0; i--) {
    const content = messages[i].content;
    if (content && content.includes('Sold items!!!')) {
      const ct = content.match(/CT\s+(\d+)/);
      const rd = content.match(/RD\s+(\d+)/);
      if (ct || rd) {
        const parts = [];
        if (ct) parts.push(ct[1]);
        if (rd) parts.push(rd[1]);
        return parts.join(' / ');
      }
    }
  }
  return null;
}

function computeBadges(messages) {
  const badges = [];
  const latest = messages[messages.length - 1];
  if (latest && latest.ts) {
    if (Math.floor(Date.now() / 1000) - latest.ts > 20 * 60) {
      badges.push('<span class="badge badge-offline">Offline</span>');
    }
  }
  for (let i = messages.length - 1; i >= 0; i--) {
    if ('unassigned' in messages[i]) {
      if (messages[i].unassigned !== null) {
        badges.push(`<span class="badge badge-to-list">To List ${messages[i].unassigned}</span>`);
      }
      break;
    }
  }
  return badges.join('');
}

function renderCard(machine, result) {
  if (result.error) {
    return `<div class="card">
      <div class="card-title">${machine}</div>
      <div class="error">${result.error}</div>
    </div>`;
  }

  const stats = getLatestStats(result.data);
  const badges = computeBadges(result.data);
  const sold = getLatestSold(result.data);

  if (!stats) {
    return `<div class="card">
      <div class="card-title">${machine}</div>
      <div class="error">No data</div>
    </div>`;
  }

  const badgesHtml = badges
    ? `<div class="badges">${badges}</div>`
    : '';

  return `<div class="card">
    <div class="card-title">${machine}</div>

    <div class="stat-block">
      <div class="label">Credits (List)</div>
      <div class="row">
        <span class="value-credit">${formatComma(stats.credits)}</span>
        <span class="sub-blue">(${stats.listCount})</span>
      </div>
    </div>

    <div class="stat-block">
      <div class="label">Bought</div>
      <div class="row">
        <span class="value">${formatM(stats.bought24h)}</span>
        <span class="sub">24h</span>
      </div>
      <div class="row">
        <span class="value">${formatM(stats.bought1h)}</span>
        <span class="sub">1h</span>
      </div>
    </div>

    ${sold ? `
    <div class="stat-block">
      <div class="row">
        <span class="label">Sold</span>
        <span class="value">${sold}</span>
      </div>
    </div>` : ''}

    ${badgesHtml}
  </div>`;
}

function render(machinesData) {
  document.getElementById('content').innerHTML =
    Object.entries(machinesData).map(([m, r]) => renderCard(m, r)).join('');
}

window.dashboard.onDataUpdate((data) => render(data));
window.dashboard.getData().then((data) => render(data));
