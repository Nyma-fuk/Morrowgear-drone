(function (root) {
  'use strict';
  const nav = [
    { id: 'operations', label: '作戦', icon: 'map', views: ['地図', '任務', '戦線'] },
    { id: 'fleet', label: '部隊', icon: 'layers', views: ['機体', 'Wing', '装備'] },
    { id: 'base', label: '基地・補給', icon: 'dock', views: ['Dock', '補給網', '回収', '母艦', '機器'] },
    { id: 'history', label: '履歴', icon: 'clock', views: ['すべて', '警告', '通信', '命令'] },
    { id: 'settings', label: '設定', icon: 'sliders-horizontal', views: ['バイザー', '通信', '表示'] }
  ];
  const roles = ['security', 'scout', 'engineer', 'cargo', 'field', 'salvage'];
  const roleNames = { security: '警備', scout: '偵察', engineer: '作業', cargo: '輸送', field: '汎用', salvage: '回収' };
  const slotLabels = ['機体', '電池', '役割', '兵装', '燃料', '整備', '弾薬', '出力', '蓄電池',
    ...Array.from({ length: 9 }, (_, i) => `回収出力 ${i + 1}`),
    ...Array.from({ length: 9 }, (_, i) => `共通補給 ${i + 1}`)];
  function roster(count) {
    return Array.from({ length: count }, (_, i) => ({ id: i + 1, label: `MG-${String(i + 1).padStart(3, '0')}`,
      wing: `W${String(Math.floor(i / 8) + 1).padStart(2, '0')}`, role: roles[i % roles.length],
      power: i === 2 ? 14 : 65 + (i * 7 % 35), docked: i % 8 >= 6,
      state: i === 2 ? '補給帰還' : i % 8 >= 6 ? '着艦' : i % 3 ? '任務中' : '待機' }));
  }
  function summary(units, ids, visibleIds) {
    const selected = units.filter(u => ids.has(u.id));
    const wings = [...new Set(selected.map(u => u.wing).filter(Boolean))];
    const full = wings.filter(w => units.filter(u => u.wing === w).every(u => ids.has(u.id))).length;
    return { count: selected.length, full, partial: wings.length - full,
      ungrouped: selected.filter(u => !u.wing).length, hidden: selected.filter(u => !visibleIds.has(u.id)).length };
  }
  function availability(units, ids, link = true, permitted = true) {
    const selected = units.filter(u => ids.has(u.id));
    const common = !link ? '通信断' : !permitted ? '操作権限なし' : !selected.length ? '対象未選択' : '';
    return { common, role: selected.every(u => u.docked) ? common : common || '着艦が必要',
      guard: common || (selected.some(u => u.role === 'security') ? '' : '警備機が必要'),
      work: common || (selected.some(u => u.role !== 'salvage') ? '' : '作業対応機が必要'),
      cargo: common || (selected.some(u => u.role === 'cargo') ? '' : '輸送機が必要') };
  }
  function arm(kind, ids) { return { kind, recipients: [...ids].sort((a, b) => a - b), points: [] }; }
  function sameRecipients(mode, ids) { return mode && mode.recipients.join(',') === [...ids].sort((a, b) => a - b).join(','); }
  function topMessage(events, completion = false) {
    return events.filter(e => !e.ack && (completion || e.kind !== 'completion'))
      .slice().sort((a, b) => a.priority - b.priority || a.sequence - b.sequence)[0] || null;
  }
  function page(items, offset, capacity) {
    const start = Math.max(0, Math.min(offset, Math.max(0, items.length - capacity)));
    return { start, items: items.slice(start, start + capacity), total: items.length };
  }
  function joinWing(units, id, wing) {
    if (units.filter(u => u.wing === wing && u.id !== id).length >= 8) return 'Wing上限8機';
    const unit = units.find(u => u.id === id);
    if (!unit) return '機体が見つからない';
    unit.wing = wing;
    return '';
  }
  const policy = { nav, roles, roleNames, slotLabels, roster, summary, availability, arm, sameRecipients, topMessage, page, joinWing };
  if (typeof module !== 'undefined' && module.exports) module.exports = policy;
  else root.OperationsPolicy = policy;
})(typeof window !== 'undefined' ? window : this);
