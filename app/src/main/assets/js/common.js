/* ===== Raphael ortak yardımcılar (her sitenin betiğinden önce eklenir) ===== */
var VER = 7;
try{Object.defineProperty(document,'hidden',{get:function(){return false;},configurable:true});Object.defineProperty(document,'visibilityState',{get:function(){return 'visible';},configurable:true});}catch(e){}
var Q = function (s, r) { try { return (r || document).querySelector(s); } catch (e) { return null; } };
var QA = function (s, r) { try { return Array.prototype.slice.call((r || document).querySelectorAll(s)); } catch (e) { return []; } };
var sleep = function (ms) { return new Promise(function (res) { setTimeout(res, ms); }); };
var vis = function (el) {
  if (!el || !el.getClientRects) return false;
  var st = window.getComputedStyle(el);
  if (st.visibility === "hidden" || st.display === "none") return false;
  if (el.getClientRects().length) return true;
  if (!document.body || !document.body.getClientRects().length) {
    for (var p = el.parentElement; p; p = p.parentElement) { var ps = window.getComputedStyle(p); if (ps.display === "none" || ps.visibility === "hidden") return false; }
    return true;
  }
  return false;
};
var firstVis = function (sels) {
  for (var i = 0; i < sels.length; i++) { var l = QA(sels[i]).filter(vis); if (l.length) return l[0]; }
  return null;
};
var norm = function (s) { return String(s || '').replace(/\s+/g, ' ').trim().toLowerCase(); };
var lbl = function (el) {
  var t = norm(el.innerText || el.textContent || '');
  return t || norm(el.getAttribute && el.getAttribute('aria-label'));
};
var isDis = function (b) {
  return !!(b.disabled || b.getAttribute('aria-disabled') === 'true' || /mat-mdc-button-disabled/.test(String(b.className)));
};
var press = function (el) {
  if (!el) return false;
  try { if (el.scrollIntoView) el.scrollIntoView({ block: 'nearest' }); } catch (e) {}
  ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function (t) {
    try { el.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window, buttons: 1 })); } catch (e) {}
  });
  return true;
};
var esc = function () {
  ['keydown', 'keyup'].forEach(function (t) {
    var e = new KeyboardEvent(t, { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true });
    (document.activeElement || document.body).dispatchEvent(e);
    document.dispatchEvent(e);
  });
};
var closeMenus = function () {
  esc();
  var bd = firstVis(['.cdk-overlay-backdrop']);
  if (bd) press(bd);
};
var waitFor = async function (fn, ms, step) {
  var t = Date.now(); step = step || 150;
  while (Date.now() - t < ms) { var v = fn(); if (v) return v; await sleep(step); }
  return null;
};
var setNative = function (el, v) {
  var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
};
var inChat = function (el) { return !!el.closest('model-response,user-query,ms-chat-turn,message-content'); };

/* düşünme düzeyi etiketleri (EN + TR) */
var LV = { low: /^(low|düşük|dusuk)\b/, medium: /^(medium|orta)\b/, high: /^(high|yüksek|yuksek)\b/, xhigh: /^(extra\s*high|xhigh|ekstra\s*yüksek|ekstra\s*yuksek|çok\s*yüksek|cok\s*yuksek)\b/ };
var levelEls = function (level, scopeSel) {
  var rx = LV[level], out = [];
  QA(scopeSel || 'body').forEach(function (sc) {
    QA('button,[role=menuitem],[role=menuitemradio],[role=menuitemcheckbox],[role=option],[role=radio],[role=tab],mat-option,mat-radio-button,mat-button-toggle,li,label', sc).forEach(function (el) {
      if (!vis(el) || inChat(el)) return;
      var t = lbl(el);
      if (t && t.length < 90 && rx.test(t)) out.push(el);
    });
  });
  out.sort(function (a, b) { return lbl(a).length - lbl(b).length; });
  return out;
};

/* ===== DOM -> Markdown (kod blokları, listeler, tablolar bozulmadan) ===== */
var SKIP = /^(script|style|svg|button|mat-icon|noscript|link|img|picture|canvas|video|audio|source-footnote|sources-carousel|sources-carousel-inline|response-element-footer)$/;
var fenceFor = function (txt) {
  var m = txt.match(/`+/g), n = 3;
  if (m) m.forEach(function (x) { if (x.length >= n) n = x.length + 1; });
  return new Array(n + 1).join('`');
};
var langOf = function (pre) {
  var code = pre.querySelector('code') || pre;
  var m = String(code.className || '').match(/language-([\w+#.-]+)/);
  if (m) return m[1];
  m = String(pre.className || '').match(/language-([\w+#.-]+)/);
  if (m) return m[1];
  var wrap = pre.closest('code-block,ms-code-block,[class*="code-block"]');
  if (wrap) {
    var sels = ['.title-text', '.code-block-decoration span', '.language-name', '[class*="language"]', 'mat-expansion-panel-header span.title-text', '.code-block-decoration'];
    for (var i = 0; i < sels.length; i++) {
      var h = wrap.querySelector(sels[i]);
      if (!h) continue;
      var t = (h.innerText || h.textContent || '').trim().split('\n')[0];
      if (t && t.length <= 20 && /^[A-Za-z0-9+#.\- ]+$/.test(t) && !/copy|kopyala|^code$|^kod$/i.test(t)) return t.toLowerCase().replace(/\s+/g, '');
    }
  }
  return '';
};
var blk = function (s) { return '\n\n' + s.trim() + '\n\n'; };
var kids = function (n, ctx) {
  var s = '';
  for (var c = n.firstChild; c; c = c.nextSibling) {
    if (!ctx.nocache && !ctx.inList && !ctx.pre && c.nextSibling && c.nodeType === 1 && n.nodeType === 1) {
      var key = (c.textContent || '').length + ':' + c.childElementCount;
      if (c.__rphK === key && c.__rphV != null) { s += c.__rphV; continue; }
      var v = conv(c, ctx);
      c.__rphK = key; c.__rphV = v;
      s += v;
    } else s += conv(c, ctx);
  }
  return s;
};
var codeBlock = function (pre) {
  var codeEl = pre.querySelector('code') || pre;
  var txt = (codeEl.textContent || '').replace(/\n$/, '');
  var f = fenceFor(txt);
  return blk(f + langOf(pre) + '\n' + txt + '\n' + f);
};
var tableMd = function (t) {
  var rows = QA('tr', t).map(function (tr) {
    return QA('th,td', tr).map(function (c) { return kids(c, {}).replace(/\|/g, '\\|').replace(/\s*\n\s*/g, ' ').trim(); });
  }).filter(function (r) { return r.length; });
  if (!rows.length) return '';
  var w = rows[0].length, out = [];
  out.push('| ' + rows[0].join(' | ') + ' |');
  out.push('|' + new Array(w + 1).join(' --- |'));
  for (var i = 1; i < rows.length; i++) out.push('| ' + rows[i].join(' | ') + ' |');
  return blk(out.join('\n'));
};
var conv = function (n, ctx) {
  if (n.nodeType === 3) return ctx.pre ? n.nodeValue : (n.nodeValue || '').replace(/\s+/g, ' ');
  if (n.nodeType !== 1) return '';
  var t = n.tagName.toLowerCase();
  if (SKIP.test(t)) return '';
  if (n.hasAttribute('data-math')) {
    var mth = n.getAttribute('data-math');
    return /block/.test(String(n.className)) ? blk('$$\n' + mth + '\n$$') : '$' + mth + '$';
  }
  if (t === 'pre') return codeBlock(n);
  if (t !== 'table' && /code-block/.test(t + ' ' + n.className) && n.querySelectorAll('pre').length === 1) return codeBlock(n.querySelector('pre'));
  if (t === 'table') return tableMd(n);
  if (/table-block/.test(t) && n.querySelector('table')) return tableMd(n.querySelector('table'));
  switch (t) {
    case 'br': return '\n';
    case 'p': return blk(kids(n, ctx));
    case 'h1': case 'h2': case 'h3': case 'h4': case 'h5': case 'h6':
      return blk(new Array(parseInt(t.charAt(1), 10) + 1).join('#') + ' ' + kids(n, ctx).trim());
    case 'strong': case 'b': { var a = kids(n, ctx); return a.trim() ? '**' + a.trim() + '**' : a; }
    case 'em': case 'i': { var b = kids(n, ctx); return b.trim() ? '*' + b.trim() + '*' : b; }
    case 'del': case 's': return '~~' + kids(n, ctx) + '~~';
    case 'code': return ctx.pre ? n.textContent : '`' + (n.textContent || '').replace(/`/g, "'") + '`';
    case 'a': { var h = n.getAttribute('href'), x = kids(n, ctx).trim(); return (h && x && /^https?:/i.test(h)) ? '[' + x + '](' + h + ')' : x; }
    case 'ul': case 'ol': {
      var nested = !!ctx.inList;
      var c2 = { pre: ctx.pre, inList: true, depth: nested ? (ctx.depth || 0) + 1 : 0, ordered: t === 'ol', idx: (parseInt(n.getAttribute('start') || '1', 10) || 1) - 1 };
      var inner = kids(n, c2);
      return nested ? '\n' + inner + '\n' : blk(inner);
    }
    case 'blockquote': return blk(kids(n, ctx).trim().split('\n').map(function (l) { return '> ' + l; }).join('\n'));
    case 'hr': return blk('---');
    case 'li': {
      ctx.idx = (ctx.idx || 0) + 1;
      var dep = ctx.depth || 0, ind = new Array(dep * 2 + 1).join(' ');
      var mk = ctx.ordered ? ctx.idx + '. ' : '- ';
      var lc = { pre: ctx.pre, inList: true, depth: dep, ordered: false, idx: 0 };
      var lt = kids(n, lc).replace(/\n{2,}/g, '\n').replace(/^\s+/, '').replace(/\s+$/, '');
      return '\n' + ind + mk + lt + '\n';
    }
    case 'div': case 'section': case 'article': case 'message-content': case 'ms-text-chunk': case 'ms-prompt-chunk':
      return '\n' + kids(n, ctx) + '\n';
    default: return kids(n, ctx);
  }
};
var mdOf = function (el, nc) {
  var s = kids(el, { nocache: !!nc }).replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n');
  s = s.replace(/^(\s*(?:- |\d+\. )[^\n]*)\n\n(?=\s*(?:- |\d+\. ))/gm, '$1\n');
  return s.trim();
};
/* İçerik kaybı olursa (beklenmeyen DOM) düz metne düş: cevap asla eksik kalmasın */
var skipRaw = function (p) {
  if (!p || p.nodeType !== 1) return false;
  var tg = p.tagName.toLowerCase();
  if (SKIP.test(tg) || isThNode(p) || tg === "mat-expansion-panel-header") return true;
  return /code-block-decoration|actions-container|title-icon|title-text|mat-expansion-indicator/.test(String(p.className || ""));
};
var rawTextLen = function (n) {
  if (!n) return 0;
  if (n.nodeType === 3) return (n.nodeValue || "").replace(/\s+/g, "").length;
  if (n.nodeType !== 1 || skipRaw(n)) return 0;
  var sum = 0;
  for (var c = n.firstChild; c; c = c.nextSibling) sum += rawTextLen(c);
  return sum;
};
var safeMd = function (el, nc) {
  var md = "";
  try { md = mdOf(el, nc); } catch (e) { md = ""; }
  var raw = rawTextLen(el);
  var got = md.replace(/[\s*#\x60>|_~\-\[\]()]+/g, "").length;
  if (raw > 40 && got < raw * 0.7) {
    var it = (el.innerText || el.textContent || "").trim();
    if (it.length > md.length) return it;
  }
  return md;
};

var isThNode = function (p) {
  if (!p || p.nodeType !== 1) return false;
  var tg = p.tagName.toLowerCase();
  if (tg === 'model-thoughts' || tg === 'ms-thought-chunk') return true;
  var cls = typeof p.className === 'string' ? p.className.split(/\s+/) : [];
  for (var i = 0; i < cls.length; i++) {
    var c = cls[i];
    if (c && /thought/i.test(c) && !/^(has|with|no|show|hide)-thoughts?$/i.test(c) && !/-(enabled|disabled)$/i.test(c)) return true;
  }
  return false;
};
var inTh = function (el, scope) {
  if (!el || !scope) return false;
  var hit = false;
  for (var p = el; p && p !== scope; p = p.parentElement || (p.getRootNode && p.getRootNode().host) || null) {
    if (isThNode(p)) hit = true;
  }
  return hit && p === scope;
};
var collectImgs = function (root, arr) {
  if (!root) return;
  QA('img', root).forEach(function (im) { arr.push(im); });
  QA('*', root).forEach(function (el) { if (el.shadowRoot) collectImgs(el.shadowRoot, arr); });
};
var inGenBox = function (el, scope) {
  for (var p = el; p && p !== scope; p = p.parentElement || (p.getRootNode && p.getRootNode().host) || null) {
    if (p.nodeType !== 1) continue;
    var tg = p.tagName.toLowerCase();
    if (tg === 'generated-image' || tg === 'single-image' || tg === 'response-image' || tg === 'ucs-generated-image') return true;
    if (typeof p.className === 'string' && /generated-image|image-container/i.test(p.className)) return true;
  }
  return false;
};
var inSkip = function (el, scope) {
  for (var p = el; p && p !== scope; p = p.parentElement || (p.getRootNode && p.getRootNode().host) || null) {
    if (p.nodeType !== 1) continue;
    var tg = p.tagName.toLowerCase();
    if (tg === 'source-footnote' || tg === 'sources-carousel' || tg === 'sources-carousel-inline' || tg === 'user-profile-picture') return true;
    if (typeof p.className === 'string' && /(avatar|profile|favicon)/i.test(p.className)) return true;
  }
  return false;
};
var imgsIn = function (scope) {
  var out = [], imgs = [];
  if (!scope) return out;
  collectImgs(scope, imgs);
  imgs.forEach(function (im) {
    if (inTh(im, scope)) return;
    if (inSkip(im, scope)) return;
    if (im.loading === 'lazy') im.loading = 'eager';
    var u = im.currentSrc || im.src || '';
    if (!/^(https?:|blob:|data:image)/i.test(u) || /^data:image\/svg/i.test(u)) return;
    var genBox = inGenBox(im, scope);
    var gUrl = !genBox && /googleusercontent\.com|ggpht\.com/i.test(u) && !/=s(16|24|32|36|40|48|64)\b/.test(u);
    var w = im.naturalWidth || im.clientWidth || 0, h = im.naturalHeight || im.clientHeight || 0;
    if (genBox || gUrl) { if ((w > 0 && w < 80) || (h > 0 && h < 80)) return; }
    else if (w < 150 || h < 150) return;
    if (out.indexOf(u) < 0) out.push(u);
  });
  if (!out.length) {
    QA('generated-image,single-image,response-image,ucs-generated-image,[class*="generated-image"],.image-container,.image-button', scope).forEach(function (el) {
      if (inTh(el, scope)) return;
      var w = el.clientWidth || 0, h = el.clientHeight || 0;
      if (w < 150 || h < 150) return;
      var bg = window.getComputedStyle(el).backgroundImage || '';
      var m = bg.match(/url\(["']?((?:https?:|blob:|data:image)[^"')]+)["']?\)/);
      if (m && !/^data:image\/svg/i.test(m[1]) && out.indexOf(m[1]) < 0) out.push(m[1]);
    });
  }
  return out.slice(0, 8);
};

/* ===== Ortak op çalıştırıcı + DOM kaydı ===== */
var ops = {};
var runOp = function (id, name, args) {
  var f = ops[name];
  Promise.resolve().then(function () { if (!f) throw new Error('op-yok:' + name); return f(args || {}); }).then(
    function (r) { AndroidBridge.opResult(id, JSON.stringify(r || {})); },
    function (e) { AndroidBridge.opResult(id, JSON.stringify({ ok: false, err: String((e && e.message) || e) })); });
  return 1;
};
ops.dom = async function (a) {
  var c = document.documentElement.cloneNode(true);
  QA('script,style,svg,link,noscript,iframe,picture,canvas,video,audio,path,meta', c).forEach(function (e) { e.remove(); });
  var keep = { id: 1, 'class': 1, role: 1, type: 1, name: 1, placeholder: 1, href: 1, src: 1, alt: 1, contenteditable: 1, disabled: 1, title: 1, mattooltip: 1, fonticon: 1,
    'data-test-id': 1, 'data-testid': 1, 'aria-label': 1, 'aria-expanded': 1, 'aria-haspopup': 1, 'aria-checked': 1, 'aria-disabled': 1, 'aria-selected': 1 };
  QA('*', c).forEach(function (e) {
    Array.prototype.slice.call(e.attributes).forEach(function (at) {
      if (!keep[at.name]) e.removeAttribute(at.name);
      else if (at.value.length > 220) e.setAttribute(at.name, at.value.slice(0, 220));
    });
  });
  var tw = document.createTreeWalker(c, NodeFilter.SHOW_TEXT), nodes = [];
  while (tw.nextNode()) nodes.push(tw.currentNode);
  nodes.forEach(function (tn) { var v = tn.nodeValue.replace(/\s+/g, ' ').trim(); if (v.length > 160) v = v.slice(0, 160) + '…'; tn.nodeValue = v; });
  var html = '<!-- ' + location.href + ' | ' + new Date().toISOString() + ' -->\n' + c.outerHTML;
  AndroidBridge.saveDom(a.site || 'site', html);
  return { ok: true, len: html.length };
};

/* ===== Dosya yükleme (Java köprüsünden parça parça okunur) ===== */
var loadFile = async function (id) {
  var info = JSON.parse(AndroidBridge.fileInfo(id)), CH = 393216, parts = [];
  for (var off = 0; off < info.size; off += CH) {
    var n = Math.min(CH, info.size - off), bin = atob(AndroidBridge.readChunk(id, off, n)), u8 = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
    parts.push(u8);
    await sleep(0);
  }
  return new File(parts, info.name, { type: info.mime || 'application/octet-stream' });
};
var ATT_SEL = 'ms-prompt-media,uploader-file-preview,file-preview,[data-test-id*="file-preview"],[class*="file-preview"],[class*="attachment"],[class*="uploaded"],ms-file-chunk,ms-image-chunk,img[src^="blob:"],[class*="file-chip"],[class*="upload-chip"]';
var attSig = function () { return QA(ATT_SEL).length; };
var dtOf = function (file) { var dt = new DataTransfer(); dt.items.add(file); return dt; };
var accepts = function (inp, file) {
  var acc = (inp.getAttribute('accept') || '').toLowerCase().trim();
  if (!acc) return true;
  var ext = '.' + (file.name.split('.').pop() || '').toLowerCase(), mt = (file.type || '').toLowerCase();
  return acc.split(',').some(function (a) {
    a = a.trim();
    return a === ext || a === mt || (/\/\*$/.test(a) && mt.indexOf(a.slice(0, -1)) === 0);
  });
};
var topAtts = function () {
  var all = QA(ATT_SEL).filter(function (e) { return vis(e) && !inChat(e); });
  return all.filter(function (e) { return !all.some(function (o) { return o !== e && o.contains(e); }); });
};
var tryInput = function (file) {
  var ai = Q('ms-add-media-button input[type=file]');
  var ins = QA('input[type=file]').filter(function (i) { return accepts(i, file); });
  var inp = (ai && accepts(ai, file)) ? ai : (ins.length ? ins[ins.length - 1] : null);
  if (!inp) return false;
  try {
    inp.files = dtOf(file).files;
    inp.dispatchEvent(new Event('input', { bubbles: true }));
    inp.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  } catch (e) { return false; }
};
var tryPaste = function (file) {
  var t = firstVis(CFG.input); if (!t) return false;
  t.focus();
  try { t.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dtOf(file), bubbles: true, cancelable: true })); return true; } catch (e) { return false; }
};
var tryDrop = function (file) {
  var t = firstVis(CFG.input); if (!t) return false;
  var zone = t.closest('rich-textarea,ms-prompt-box,ms-prompt-input-wrapper,.input-area,[class*="input"]') || t, dt = dtOf(file);
  ['dragenter', 'dragover', 'drop'].forEach(function (n) {
    try { zone.dispatchEvent(new DragEvent(n, { dataTransfer: dt, bubbles: true, cancelable: true })); } catch (e) {}
  });
  return true;
};
var openUploadMenu = async function () {
  var b = firstVis(['button[aria-label*="upload" i]', 'button[aria-label*="attach" i]', 'button[aria-label*="insert" i]', 'button[aria-label*="dosya" i]', 'button[aria-label*="ekle" i]', 'button[aria-label*="add" i]']);
  if (!b) return false;
  press(b);
  await sleep(600);
  var it = QA('[role=menuitem],button,li').filter(vis).filter(function (x) { return /upload (files?|from)|dosya(ları)? yükle|upload file/i.test(lbl(x)); })[0];
  if (it) { press(it); await sleep(700); }
  return true;
};
ops.attach = async function (a) {
  var file = await loadFile(a.id), before = attSig(), mBefore = QA('ms-prompt-media').length;
  var hasAiInp = !!Q('ms-add-media-button input[type=file]');
  var methods = hasAiInp ? [
    function () { return tryInput(file); },
    function () { return tryPaste(file); },
    function () { return tryDrop(file); },
    async function () { var m = await openUploadMenu(); await sleep(400); if (attSig() > before) return true; var r = tryInput(file); closeMenus(); return r || m; }
  ] : [
    function () { return tryPaste(file); },
    function () { return tryInput(file); },
    function () { return tryDrop(file); },
    async function () { var m = await openUploadMenu(); await sleep(400); if (attSig() > before) return true; var r = tryInput(file); closeMenus(); return r || m; }
  ];
  for (var i = 0; i < methods.length; i++) {
    var sent = false;
    try { sent = await methods[i](); } catch (e) {}
    if (!sent) continue;
    var ok = await waitFor(function () { return attSig() > before; }, 5000, 250);
    if (ok) {
      await waitFor(function () {
        return !QA('mat-progress-spinner,mat-progress-bar,[role=progressbar],[class*="uploading"]').filter(function (e) { return vis(e) && !inChat(e); }).length;
      }, 30000, 400);
      var medias = QA('ms-prompt-media'), tok = '', wname = '', idx = -1;
      if (medias.length > mBefore) {
        var item = medias[medias.length - 1];
        idx = medias.length - 1;
        await waitFor(function () {
          var tc = Q('[data-test-id="token-count"], ms-token-status', item);
          var t = tc ? (tc.innerText || tc.textContent || '').replace(/\s+/g, ' ').trim() : '';
          if (t && !/uploading|processing|calculating|loading|yüklen/i.test(t)) { tok = t; return true; }
          return false;
        }, 10000, 250);
        var nm = Q('.name', item);
        if (nm) wname = (nm.innerText || nm.textContent || '').replace(/\s+/g, ' ').trim();
      } else {
        var tops = topAtts();
        if (tops.length) idx = tops.length - 1;
      }
      await sleep(250);
      return { ok: true, method: i, tokens: tok, wname: wname, idx: idx };
    }
  }
  return { ok: false, err: 'ek-eklenemedi' };
};
ops.removeAttach = async function (a) {
  var want = norm(a.wname || a.name || '');
  var medias = QA('ms-prompt-media').filter(vis);
  if (medias.length) {
    var hit = null;
    if (want) {
      for (var i = medias.length - 1; i >= 0; i--) {
        var nm = Q('.name', medias[i]);
        var nt = nm ? norm(nm.innerText || nm.textContent || '') : '';
        if (nt && (nt === want || nt.indexOf(want) >= 0 || want.indexOf(nt) >= 0)) { hit = medias[i]; break; }
      }
    }
    if (!hit && typeof a.idx === 'number' && a.idx >= 0 && a.idx < medias.length) hit = medias[a.idx];
    if (!hit && medias.length === 1) hit = medias[0];
    if (hit) {
      var rb = Q('button[aria-label*="remove" i],button[mattooltip*="remove" i],button[aria-label*="delete" i],button[aria-label*="kaldır" i],button[aria-label*="sil" i],button[aria-label*="clear" i]', hit)
        || QA('button', hit).filter(function (b) { return norm(b.innerText || b.textContent || '') === 'close'; })[0];
      if (rb) { press(rb); await sleep(200); return { ok: true }; }
    }
  }
  var items = topAtts();
  if (items.length) {
    var ghit = null;
    if (want) {
      for (var j = items.length - 1; j >= 0; j--) {
        var im = Q('img', items[j]);
        var txt = norm((items[j].innerText || items[j].textContent || '') + ' ' + ((im && im.alt) || '') + ' ' + (items[j].getAttribute('aria-label') || ''));
        if (txt && txt.indexOf(want) >= 0) { ghit = items[j]; break; }
      }
    }
    if (!ghit && typeof a.idx === 'number' && a.idx >= 0 && a.idx < items.length) ghit = items[a.idx];
    if (!ghit && items.length === 1) ghit = items[0];
    if (ghit) {
      var gb = Q('button[aria-label*="remove" i],button[aria-label*="cancel" i],button[aria-label*="delete" i],button[aria-label*="kaldır" i],button[aria-label*="sil" i],button[aria-label*="close" i],button', ghit);
      if (gb) { press(gb); await sleep(200); return { ok: true }; }
    }
  }
  return { ok: false, err: 'kaldirilamadi' };
};

/* ===== Üretilen görselleri uygulamaya al ===== */
ops.grabImages = async function (a) {
  var files = [], failed = [];
  for (var i = 0; i < a.urls.length; i++) {
    var u = a.urls[i];
    try {
      var r = await fetch(u, { credentials: 'include' });
      if (!r.ok) throw 0;
      var b = await r.blob();
      if (!b.size) throw 0;
      var du = await new Promise(function (res, rej) { var fr = new FileReader(); fr.onload = function () { res(fr.result); }; fr.onerror = rej; fr.readAsDataURL(b); });
      var p = AndroidBridge.saveImage(b.type || 'image/png', du.slice(du.indexOf(',') + 1));
      if (p) files.push(p); else throw 0;
    } catch (e) {
      var ok = false;
      try {
        var all = []; collectImgs(document, all);
        var im = all.filter(function (x) { return (x.currentSrc || x.src) === u && x.complete && x.naturalWidth > 0; })[0];
        if (im) {
          var cv = document.createElement('canvas');
          cv.width = im.naturalWidth; cv.height = im.naturalHeight;
          cv.getContext('2d').drawImage(im, 0, 0);
          var du2 = cv.toDataURL('image/png');
          var p2 = AndroidBridge.saveImage('image/png', du2.slice(du2.indexOf(',') + 1));
          if (p2) { files.push(p2); ok = true; }
        }
      } catch (e2) {}
      if (!ok) failed.push(u);
    }
  }
  return { ok: true, files: files, failed: failed };
};
