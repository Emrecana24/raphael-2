/* ===== aistudio.google.com sürücüsü =====
   Site arayüzünü değiştirirse SADECE aşağıdaki CFG listelerini güncelle. */
var CFG = {
  input: ['ms-prompt-input-wrapper ms-autosize-textarea textarea', 'ms-autosize-textarea textarea', 'textarea[aria-label*="prompt" i]', 'textarea[aria-label="Type something"]', 'textarea'],
  run: ['button.run-button', 'button[aria-label="Run"]', 'ms-run-button button'],
  settingsToggle: ['button[aria-label*="run settings" i]', 'button[aria-label*="Toggle run settings" i]'],
  modelCard: ['ms-model-selector button', 'ms-model-selector']
};
var THOUGHT = '.thought-container,ms-thought-chunk,[class*="thought"]';
var modelConts = function () { return QA('ms-chat-turn .chat-turn-container.model'); };
var modelCount = function () { return modelConts().length; };
var runBtn = function () { return firstVis(CFG.run) || Q('button.run-button'); };
var isGen = function () {
  var b = runBtn(); if (!b) return false;
  var t = norm((b.getAttribute('aria-label') || '') + ' ' + (b.innerText || ''));
  if (/\bstop\b|durdur/.test(t)) return true;
  var sp = Q('.stoppable-spinner', b);
  return !!(sp && vis(sp));
};
var replyNodes = function (cont) {
  var nodes = [];
  QA('ms-prompt-chunk', cont).filter(function (c) { return !inTh(c, cont); }).forEach(function (ch) {
    QA('ms-cmark-node', ch).filter(function (n) { return !n.parentElement.closest('ms-cmark-node'); }).forEach(function (n) { nodes.push(n); });
  });
  if (!nodes.length) {
    nodes = QA('ms-cmark-node', cont).filter(function (n) { return !inTh(n, cont) && !n.parentElement.closest('ms-cmark-node'); });
  }
  return nodes;
};
/* son model turunun anlık görüntüsü (metin + görseller) */
var snap = function () {
  var conts = modelConts(), cont = conts[conts.length - 1] || null;
  var nodes = cont ? replyNodes(cont) : [], imgs = imgsIn(cont);
  var raw = nodes.map(function (n) { return n.textContent || ''; }).join('\n');
  return { nodes: nodes, imgs: imgs, rawLen: raw.length,
    cur: conts.length + ':' + raw.length + ':' + raw.slice(-48) + '|' + imgs.length + ':' + (imgs.length ? imgs[imgs.length - 1].slice(-30) : '') };
};
var openSettings = async function (finder) {
  var el = finder();
  if (el && vis(el)) return { el: el, opened: false };
  var tg = firstVis(CFG.settingsToggle);
  if (!tg) return { el: el && vis(el) ? el : null, opened: false };
  press(tg);
  await sleep(700);
  el = finder();
  return { el: (el && vis(el)) ? el : null, opened: true, toggle: tg };
};
var closeSettings = function (r) { if (r && r.opened) { var tg = firstVis(CFG.settingsToggle) || r.toggle; if (tg) press(tg); } };
var thinkSel = function () {
  var el = Q('ms-thinking-level-setting mat-select') || Q('mat-select[aria-label*="hinking" i]');
  if (el) return el;
  var c = QA('mat-select').filter(function (x) {
    var p = x.parentElement;
    for (var i = 0; i < 2 && p; i++, p = p.parentElement) if (/thinking level|düşünme/.test(norm(p.innerText).slice(0, 160))) return true;
    return false;
  });
  return c[0] || null;
};
var modelItems = function () {
  var root = Q('.cdk-overlay-container'); if (!root) return [];
  var seen = {}, out = [];
  QA('button,[role=option],[role=button],[role=listitem],mat-option,a,[class*="model-card"],[class*="model-cell"],[class*="model-option"]', root).filter(vis).forEach(function (el) {
    var lines = (el.innerText || '').split('\n').map(function (x) { return x.trim(); }).filter(Boolean);
    if (!lines.length) return;
    var name = lines[0];
    if (name.length < 3 || name.length > 60 || seen[name]) return;
    if (!/gemini|gemma|imagen|veo|learnlm|nano|lyria/i.test(lines.join(' '))) return;
    seen[name] = 1; out.push({ name: name, el: el });
  });
  return out;
};
var openModelPicker = async function () {
  var r = await openSettings(function () { return firstVis(CFG.modelCard); });
  if (!r.el) return { ok: false, r: r };
  press(r.el);
  await sleep(600);
  return { ok: true, r: r };
};

ops.send = async function (a) {
  var ta = await waitFor(function () { return firstVis(CFG.input); }, 10000);
  if (!ta) return { ok: false, err: 'mesaj-kutusu-yok' };
  var m0 = modelCount(), base = snap().cur;
  ta.focus();
  if (a.text) setNative(ta, a.text);
  await sleep(250);
  var btn = await waitFor(function () { var b = runBtn(); return (b && !isDis(b) && !isGen()) ? b : null; }, a.waitSend || 6000);
  if (btn) press(btn);
  else {
    ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, ctrlKey: true, bubbles: true }));
  }
  var started = await waitFor(function () { return modelCount() > m0 || isGen(); }, 10000, 200);
  return started ? { ok: true, m0: m0, base: base } : { ok: false, err: 'gonderilemedi' };
};

ops.setThinking = async function (a) {
  var lv = a.level;
  if (!LV[lv]) return { ok: false, err: 'gecersiz-seviye' };
  var r = await openSettings(thinkSel);
  if (!r.el) { closeSettings(r); return { ok: false, err: 'dusunme-secici-yok' }; }
  if (LV[lv].test(lbl(r.el))) { closeSettings(r); return { ok: true, already: true }; }
  press(r.el);
  var opt = await waitFor(function () { var l = levelEls(lv, '.cdk-overlay-container'); return l.length ? l[0] : null; }, 3000);
  if (!opt) { closeMenus(); closeSettings(r); return { ok: false, err: 'secenek-yok' }; }
  press(opt);
  await sleep(450);
  closeMenus();
  closeSettings(r);
  return { ok: true };
};

ops.listModels = async function () {
  var o = await openModelPicker();
  if (!o.ok) { closeSettings(o.r); return { ok: false, err: 'model-secici-yok' }; }
  var items = await waitFor(function () { var l = modelItems(); return l.length ? l : null; }, 3500);
  var names = (items || []).map(function (x) { return x.name; });
  closeMenus(); await sleep(250); closeSettings(o.r);
  return { ok: names.length > 0, models: names, err: names.length ? '' : 'model-bulunamadi' };
};

ops.setModel = async function (a) {
  var o = await openModelPicker();
  if (!o.ok) { closeSettings(o.r); return { ok: false, err: 'model-secici-yok' }; }
  var want = norm(a.name);
  var items = await waitFor(function () { var l = modelItems(); return l.length ? l : null; }, 3500) || [];
  var hit = items.filter(function (x) { return norm(x.name) === want; })[0] || items.filter(function (x) { return norm(x.name).indexOf(want) >= 0 || want.indexOf(norm(x.name)) >= 0; })[0];
  if (!hit) { closeMenus(); await sleep(250); closeSettings(o.r); return { ok: false, err: 'model-yok' }; }
  press(hit.el);
  await sleep(700);
  closeMenus();
  closeSettings(o.r);
  return { ok: true };
};

window.__rph = {
  ver: VER, site: 'aistudio',
  probe: function () {
    var login = /(^|\.)accounts\.google\.com$/.test(location.hostname);
    return { url: location.href, login: login, guest: false, ready: !!firstVis(CFG.input), gen: isGen(), m: modelCount() };
  },
  state: function (sig, m0, base, sawGen) {
    var o = { m: 0, gen: false, sig: sig, fresh: false };
    try {
      var m = modelCount(); o.m = m; o.gen = isGen();
      var sn = snap(); o.dbg = 'm=' + m + ' dugum=' + sn.nodes.length + ' len=' + sn.rawLen + ' img=' + sn.imgs.length;
      o.fresh = m0 < 0 || m > m0 || (!!sawGen && sn.cur !== base);
      if (!o.fresh) return o;
      o.sig = sn.cur;
      if (sn.cur !== sig) {
        o.text = sn.nodes.map(safeMd).filter(Boolean).join('\n\n');
        o.imgs = sn.imgs;
      }
    } catch (e) { o.err = String(e); }
    return o;
  },
  stop: function () { if (isGen()) { press(runBtn()); return { ok: true }; } return { ok: false }; },
  run: runOp
};
