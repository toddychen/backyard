function initials(name) {
  if (!name) return '?';
  return name.trim().split(/\s+/).map(w => w[0]).join('').toUpperCase().slice(0, 2);
}

function avatarColor(id) {
  const colors = [
    '#4a9eff', '#e8564b', '#2eb886', '#e9a82b', '#9c6ef0', '#e05c9a',
    '#14b8a6', '#c026d3', '#06b6d4', '#f43f5e', '#0284c7', '#7c3aed',
  ];
  const hex = (id || '').replace(/-/g, '').slice(-6);
  return colors[parseInt(hex || '0', 16) % colors.length];
}
