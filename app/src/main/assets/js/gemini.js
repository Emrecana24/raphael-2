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

ops.send = async function (a) {
  var inp = await waitFor(function () { return firstVis(CFG.input); }, 10000);
  if (!inp) return { ok: false, err: 'mesaj-kutusu-yok' };
  var m0 = modelCount(), u0 = QA('user-query').length, base = snap().cur;
  inp.focus();
  if (a.text) {
  if (!a.keep) { document.execCommand('selectAll', false, null); document.execCommand('delete', false, null); }
  document.execCommand('insertText', false, a.text);
  await sleep(200);
  if (empty(inp)) {
    try {
      var dt = new DataTransfer(); dt.setData('text/plain', a.text);
      inp.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
    } catch (e) {}
    await sleep(300);
  }
  if (empty(inp)) {
    inp.textContent = a.text;
    inp.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: a.text }));
    await sleep(300);
  }
  if (empty(inp)) return { ok: false, err: 'metin-yazilamadi' };
  }
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
