/* ===== gemini.google.com sürücüsü =====
   Site arayüzünü değiştirirse SADECE aşağıdaki CFG listelerini güncelle. */
var CFG = {
  input: ['rich-textarea .ql-editor[contenteditable="true"]', 'div.ql-editor[contenteditable="true"]', 'rich-textarea [contenteditable="true"]', 'div[contenteditable="true"][role="textbox"]', 'div[contenteditable="true"]'],
  send: ['button.send-button:not(.stop)', 'button[aria-label="Send message"]', 'button[aria-label*="Send" i]', 'button[aria-label*="gönder" i]', 'button[mattooltip*="Send" i]'],
  stop: ['button.send-button.stop', 'button.stop', 'button[aria-label*="Stop" i]', 'button[aria-label*="durdur" i]'],
  picker: ['button[data-test-id="bard-mode-menu-button"]', 'bard-mode-switcher button', 'button[aria-label*="mode picker" i]', 'button[aria-label*="model" i]'],
  levelRow: /thinking|düşünme|effort|çaba|reasoning/
};
var modelCount = function () { var n = QA('model-response').length; if (!n) n = QA('message-content').length; return n; };
var isGen = function () {
  if (firstVis(CFG.stop)) return true;
  var r = lastResp();
  if (!r) return false;
  if (Q('.image-gen-shimmer-placeholder.gem-shimmer-active', r)) return true;
  var all = [];
  collectImgs(r, all);
  all.forEach(function (im) { if (im.loading === 'lazy') im.loading = 'eager'; });
  if (all.some(function (im) {
    return inGenBox(im, r) && !!(im.currentSrc || im.src) && !im.complete;
  })) return true;
  if (Q('generated-image, single-image, response-image, ucs-generated-image', r) && !imgsIn(r).length && !Q('message-actions, .response-footer.complete', r)) return true;
  return false;
};
var lastResp = function () { var r = QA('model-response'); return r.length ? r[r.length - 1] : null; };
var lastReplyEl = function (scope) {
  var c = QA('message-content', scope || document).filter(function (e) { return !inTh(e, scope); });
  var el = c[c.length - 1];
  if (!el) return null;
  return Q('.markdown', el) || el;
};
/* son cevabın anlık görüntüsü (metin + görseller) */
var snap = function () {
  var scope = lastResp(), el = lastReplyEl(scope), imgs = imgsIn(scope), raw = el ? (el.textContent || '') : '';
  return { el: el, imgs: imgs, rawLen: raw.length,
    cur: modelCount() + ':' + raw.length + ':' + raw.slice(-48) + '|' + imgs.length + ':' + (imgs.length ? imgs[imgs.length - 1].slice(-30) : '') };
};
var pickerBtn = function () {
  var b = firstVis(CFG.picker);
  if (b) return b;
  var rx = /(auto|fast|hızlı|flash|pro\b|thinking|3\.\d|low|medium|high|düşük|orta|yüksek)/;
  var c = QA('button[aria-haspopup],button[aria-expanded]').filter(function (x) { return vis(x) && !inChat(x) && rx.test(lbl(x)) && lbl(x).length < 40; });
  c.sort(function (a, b) { return b.getBoundingClientRect().top - a.getBoundingClientRect().top; });
  return c[0] || null;
};
var empty = function (inp) { return !(inp.innerText || inp.textContent || '').trim(); };
var rawLen = function (s) { return String(s || '').replace(/\s+/g, '').length; };
var gotOk = function (inp, txt) { var w = rawLen(txt); if (!w) return true; var g = rawLen(inp.innerText || inp.textContent || ''); return w <= 20 ? g >= w : g >= Math.floor(w * 0.95); };
var clearInp = function (inp) { inp.focus(); document.execCommand('selectAll', false, null); document.execCommand('delete', false, null); if (!empty(inp)) { while (inp.firstChild) inp.removeChild(inp.firstChild); inp.dispatchEvent(new Event('input', { bubbles: true })); } };
var sendReady = function () { var b = firstVis(CFG.send); return (b && !isDis(b)) ? b : null; };
var putText = async function (inp, raw, keep, needBtn) {
  var txt = String(raw || '').replace(/\r\n?/g, '\n'); if (!txt) return true;
  if (!keep) clearInp(inp);
  if (txt.indexOf('\n') < 0) { document.execCommand('insertText', false, txt); await sleep(200); if (gotOk(inp, txt)) return true; }
  else {
    try { var dt = new DataTransfer(); dt.setData('text/plain', txt); inp.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true })); } catch (e) {}
    await sleep(250); if (gotOk(inp, txt) && (!needBtn || await waitFor(sendReady, 1500))) return true;
    clearInp(inp); var frag = document.createDocumentFragment(), lines = txt.split('\n');
    for (var k = 0; k < lines.length; k++) { var p = document.createElement('p'); if (lines[k]) p.appendChild(document.createTextNode(lines[k])); else p.appendChild(document.createElement('br')); frag.appendChild(p); }
    inp.appendChild(frag); if (inp.classList) inp.classList.remove('ql-blank');
    inp.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertFromPaste', data: txt })); inp.dispatchEvent(new Event('change', { bubbles: true }));
    await sleep(300); if (gotOk(inp, txt) && (!needBtn || await waitFor(sendReady, 1500))) return true;
    clearInp(inp);
    for (var m = 0; m < lines.length; m++) { if (lines[m]) document.execCommand('insertText', false, lines[m]); if (m < lines.length - 1) document.execCommand('insertLineBreak', false, null); if (m % 50 === 49) await sleep(0); }
    await sleep(250); if (gotOk(inp, txt) && (!needBtn || await waitFor(sendReady, 1500))) return true;
  }
  clearInp(inp); inp.textContent = txt; inp.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: txt })); await sleep(300); return gotOk(inp, txt);
};

ops.send = async function (a) {
  var inp = await waitFor(function () { return firstVis(CFG.input); }, 10000);
  if (!inp) return { ok: false, err: 'mesaj-kutusu-yok' };
  var m0 = modelCount(), u0 = QA('user-query').length, base = snap().cur;
  inp.focus();
  if (a.text && !(await putText(inp, a.text, a.keep, !(a.waitSend > 6000)))) return { ok: false, err: 'metin-eksik-yazildi' };
  var btn = await waitFor(function () { var b = firstVis(CFG.send); return (b && !isDis(b)) ? b : null; }, a.waitSend || 6000);
  if (btn) press(btn);
  else {
    ['keydown', 'keypress', 'keyup'].forEach(function (t) {
      inp.dispatchEvent(new KeyboardEvent(t, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
    });
  }
  var started = await waitFor(function () { return modelCount() > m0 || isGen() || QA('user-query').length > u0; }, 10000, 200);
  return started ? { ok: true, m0: m0, base: base } : { ok: false, err: 'gonderilemedi' };
};

ops.setThinking = async function (a) {
  var lv = a.level;
  if (lv === 'xhigh' || !LV[lv]) return { ok: false, err: 'gecersiz-seviye' };
  var pk = await waitFor(pickerBtn, 5000);
  if (!pk) return { ok: false, err: 'secici-yok' };
  press(pk);
  await sleep(500);
  var find = function () { var l = levelEls(lv); return l.length ? l[0] : null; };
  var el = await waitFor(find, 2500);
  if (!el) {
    var rows = QA('button,[role=menuitem],[role=option],li,div[role=button]').filter(function (x) { return vis(x) && !inChat(x) && CFG.levelRow.test(lbl(x)) && lbl(x).length < 60; });
    rows.sort(function (x, y) { return lbl(x).length - lbl(y).length; });
    if (rows.length) { press(rows[0]); await sleep(450); el = await waitFor(find, 2500); }
  }
  if (!el) { closeMenus(); return { ok: false, err: 'seviye-bulunamadi' }; }
  press(el);
  await sleep(500);
  closeMenus();
  return { ok: true };
};

var uqText = function (uq) {
  var qt = Q('.query-text', uq);
  if (qt) { var ls = QA('.query-text-line', qt); if (ls.length) return ls.map(function (l) { return (l.innerText || l.textContent || '').replace(/\u00a0/g, ' '); }).join('\n').trim(); return (qt.innerText || qt.textContent || '').trim(); }
  var c = uq.cloneNode(true); QA('button,mat-icon,svg,[role=button]', c).forEach(function (x) { x.remove(); });
  var t = (c.innerText || c.textContent || '').trim(); if (t) return t;
  var fn = Q('[class*="file-name"],[data-test-id*="file-name"],.file-title', uq); return fn ? '[Dosya: ' + (fn.innerText || fn.textContent || '').trim() + ']' : '';
};
var replyMds = function (mr) {
  var cs = QA('message-content', mr).filter(function (e) { return !inTh(e, mr); });
  return cs.map(function (c) { return safeMd(Q('.markdown', c) || c, true); }).filter(Boolean).join('\n\n');
};
ops.listChats = async function () {
  var hasChats = function () { return QA('[data-test-id="conversation"] a').filter(vis); };
  var opened = false;
  if (!Q('mat-sidenav.mat-drawer-opened') || !hasChats().length) {
    var mb = firstVis(['[data-test-id="side-nav-menu-button"] button', 'side-nav-menu-button button', 'button[aria-label="Ana menü"]', 'button[aria-label*="menü" i]', 'button[aria-label*="menu" i]']);
    if (mb) { press(mb); opened = true; }
  }
  await waitFor(function () { return hasChats().length > 0; }, 4500, 250);
  await sleep(300);
  var seen = {}, list = [];
  QA('[data-test-id="conversation"] a').forEach(function (a) {
    var href = a.getAttribute('href') || '';
    if (!/\/app\/[A-Za-z0-9_-]+/.test(href) || seen[href]) return;
    seen[href] = 1; var tt = Q('.title-text', a);
    var title = (tt ? (tt.innerText || tt.textContent) : (a.getAttribute('aria-label') || a.innerText || a.textContent || '')).replace(/\s+/g, ' ').trim() || 'Sohbet';
    try { list.push({ title: title, href: href, url: new URL(href, location.origin).href }); } catch (e) {}
  });
  if (opened) { var cb = firstVis(['.close-sidenav-button button', 'mat-sidenav button[aria-label*="kapat" i]', 'mat-sidenav button[aria-label*="close" i]', '.mat-drawer-backdrop']); if (cb) { press(cb); await sleep(200); } }
  return { ok: true, chats: list };
};
ops.readChat = async function () {
  var root = function () { return Q('#chat-history') || Q('infinite-scroller[data-test-id="chat-history-container"]') || document; };
  var turns = function () { return QA('user-query, model-response', root()); };
  if (!(await waitFor(function () { return turns().length > 0; }, 12000, 300))) return { ok: false, err: 'sohbet-yuklenemedi' };
  var prev = '', st = 0;
  for (var w = 0; w < 14; w++) { var ts = turns(); var cur = ts.length + ':' + ts.map(function (x) { return (x.textContent || '').length; }).join(','); if (cur === prev && !isGen()) { if (++st >= 2) break; } else { prev = cur; st = 0; } await sleep(400); }
  var sc = QA('#chat-history, infinite-scroller[data-test-id="chat-history-container"]').filter(function (x) { return x.scrollHeight > x.clientHeight + 20; })[0];
  if (sc && sc.scrollTop > 0) { for (var u = 0; u < 2; u++) { var n0 = turns().length; sc.scrollTop = 0; await sleep(650); if (turns().length <= n0) break; } sc.scrollTop = sc.scrollHeight; await sleep(250); }
  var ai = []; collectImgs(root(), ai); ai.forEach(function (im) { if (im.loading === 'lazy') im.loading = 'eager'; }); await sleep(250);
  var out = [];
  turns().forEach(function (el) {
    var tg = el.tagName.toLowerCase();
    if (tg === 'user-query') { var t = uqText(el), im = imgsIn(el); if (t || im.length) out.push({ role: 'u', text: t, imgs: im }); }
    else if (tg === 'model-response') { var mt = replyMds(el), mim = imgsIn(el); if (mt || mim.length) out.push({ role: 'a', text: mt, imgs: mim }); }
  });
  return out.length ? { ok: true, turns: out } : { ok: false, err: 'mesaj-bulunamadi' };
};

window.__rph = {
  ver: VER, site: 'gemini',
  probe: function () {
    var login = /(^|\.)accounts\.google\.com$/.test(location.hostname);
    var guest = !!firstVis(['a[href*="accounts.google.com/ServiceLogin"]', 'a[href*="accounts.google.com/signin"]']);
    return { url: location.href, login: login, guest: guest, ready: !!firstVis(CFG.input), gen: isGen(), m: modelCount() };
  },
  state: function (sig, m0, base, sawGen) {
    var o = { m: 0, gen: false, sig: sig, fresh: false };
    try {
      var m = modelCount(); o.m = m; o.gen = isGen();
      var sn = snap(); o.dbg = 'm=' + m + ' len=' + sn.rawLen + ' img=' + sn.imgs.length;
      if (!sn.imgs.length && !sn.rawLen) {
        var sc = lastResp(), di = [];
        if (sc) collectImgs(sc, di);
        if (di.length) { var d0 = di[0]; o.dbg += ' c=' + di.length + ':' + (d0.naturalWidth || 0) + 'x' + (d0.naturalHeight || 0) + ' th=' + (inTh(d0, sc) ? 1 : 0) + ' gb=' + (inGenBox(d0, sc) ? 1 : 0); }
      }
      o.fresh = m0 < 0 || m > m0 || (!!sawGen && sn.cur !== base);
      if (!o.fresh) return o;
      o.sig = sn.cur;
      if (sn.cur !== sig) { o.text = sn.el ? safeMd(sn.el) : ''; o.imgs = sn.imgs; }
    } catch (e) { o.err = String(e); }
    return o;
  },
  stop: function () { var b = firstVis(CFG.stop); if (b) { press(b); return { ok: true }; } return { ok: false }; },
  run: runOp
};
