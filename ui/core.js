/* Rendering helpers, store, backend bridge, i18n and formatting. */
'use strict';

// ---- htm + style strings ---------------------------------------------------
const hoverCache = new Map();
let hoverSheet = null;

function parseStyle(s) {
  const o = {};
  for (const decl of s.split(';')) {
    const i = decl.indexOf(':');
    if (i < 0) continue;
    let k = decl.slice(0, i).trim();
    const v = decl.slice(i + 1).trim();
    if (!k) continue;
    if (!k.startsWith('--')) k = k.replace(/^-webkit-/, 'Webkit-').replace(/-([a-z])/g, (_, c) => c.toUpperCase());
    o[k] = v;
  }
  return o;
}

function hoverClass(css) {
  let c = hoverCache.get(css);
  if (!c) {
    c = 'hv' + hoverCache.size;
    hoverCache.set(css, c);
    if (!hoverSheet) hoverSheet = document.head.appendChild(document.createElement('style')).sheet;
    const body = css.split(';').map(x => x.trim()).filter(Boolean).map(x => x + ' !important').join(';');
    hoverSheet.insertRule(`.${c}:hover{${body}}`, hoverSheet.cssRules.length);
  }
  return c;
}

function h(type, props, ...children) {
  if (props) {
    if (typeof props.style === 'string') props.style = parseStyle(props.style);
    if (props['style-hover'] !== undefined) {
      const hc = hoverClass(props['style-hover']);
      props.className = props.className ? props.className + ' ' + hc : hc;
      delete props['style-hover'];
    }
    if (props.class !== undefined) { props.className = [props.className, props.class].filter(Boolean).join(' '); delete props.class; }
    if (props.maxlength !== undefined) { props.maxLength = props.maxlength; delete props.maxlength; }
  }
  return React.createElement(type, props, ...children);
}
const html = htm.bind(h);

// ---- store ----------------------------------------------------------------
const store = {
  state: {
    ready: false, me: {}, settings: null, devices: [], transfers: [], clips: [], history: [],
    audio: { on: false, sources: [], remote: {}, incoming: null, outputs: [] }, pair: null, qr: null,
  },
  listeners: new Set(),
  set(patch) { this.state = { ...this.state, ...patch }; this.listeners.forEach(f => f()); },
};
const events = new EventTarget();

window.__ld = {
  emit(name, data) {
    if (name === 'state') store.set(data);
    else events.dispatchEvent(new CustomEvent(name, { detail: data }));
  },
};

function useStore() {
  const [, force] = React.useState(0);
  React.useEffect(() => {
    const f = () => force(x => x + 1);
    store.listeners.add(f);
    return () => store.listeners.delete(f);
  }, []);
  return store.state;
}

function useEvent(name, fn) {
  const ref = React.useRef(fn);
  ref.current = fn;
  React.useEffect(() => {
    const l = e => ref.current(e.detail);
    events.addEventListener(name, l);
    return () => events.removeEventListener(name, l);
  }, [name]);
}

function api(name, ...args) {
  const p = window.pywebview && window.pywebview.api;
  if (!p) return Promise.reject(new Error('backend is not ready'));
  return p.call(name, args);
}

function jslog(msg) {
  if (window.pywebview && window.pywebview.api && window.pywebview.api.call) api('jslog', String(msg)).catch(() => {});
  else (window.__errs = window.__errs || []).push(String(msg));
}
window.addEventListener('error', e => jslog(e.message + ' @ ' + (e.filename || '').split('/').pop() + ':' + e.lineno));
window.addEventListener('unhandledrejection', e => jslog('unhandled: ' + (e.reason && (e.reason.stack || e.reason.message) || e.reason)));

onBackendReady(() => (window.__errs || []).splice(0).forEach(jslog));

function onBackendReady(fn) {
  // pywebviewready may fire before the API object is filled in: wait for api.call itself
  const ready = () => window.pywebview && window.pywebview.api && window.pywebview.api.call;
  if (ready()) { fn(); return; }
  const timer = setInterval(() => { if (ready()) { clearInterval(timer); fn(); } }, 50);
}

// ---- i18n -----------------------------------------------------------------
let LANG = 'ru';
function setLang(l) { LANG = l === 'en' ? 'en' : 'ru'; }

function t(s, ...args) {
  let out = LANG === 'en' ? ((window.I18N_EN || {})[s] ?? s) : s;
  if (args.length) out = out.replace(/\{(\d)\}/g, (_, i) => fmtArg(args[+i]));
  return out;
}

// Arguments sent by the backend: {size: bytes}, {files: n}, {dur: seconds} or text keys.
function fmtArg(a) {
  if (a && typeof a === 'object') {
    if ('size' in a) return fmtSize(a.size);
    if ('files' in a) return npl(a.files, 'file');
    if ('dur' in a) return durationText(a.dur);
    return '';
  }
  if (typeof a === 'string') return a[0] === '{' ? subText(a) : t(a);
  return String(a);
}

// History "sub" lines are stored as {"k": key, "a": [args]} so they follow the UI language.
function subText(sub) {
  if (sub && sub[0] === '{') {
    try { const o = JSON.parse(sub); return t(o.k, ...(o.a || [])); } catch (e) { /* plain text */ }
  }
  return t(sub || '');
}

const PLURALS = {
  ru: {
    device: ['устройство', 'устройства', 'устройств'], trusted: ['доверенное', 'доверенных', 'доверенных'],
    item: ['объект', 'объекта', 'объектов'], file: ['файл', 'файла', 'файлов'], element: ['элемент', 'элемента', 'элементов'],
  },
  en: {
    device: ['device', 'devices'], trusted: ['trusted', 'trusted'], item: ['item', 'items'], file: ['file', 'files'], element: ['item', 'items'],
  },
};

function pl(n, key) {
  const f = PLURALS[LANG][key];
  if (LANG === 'en') return n === 1 ? f[0] : f[1];
  const a = n % 10, b = n % 100;
  return a === 1 && b !== 11 ? f[0] : a >= 2 && a <= 4 && (b < 10 || b >= 20) ? f[1] : f[2];
}
const npl = (n, key) => n + ' ' + pl(n, key);

// ---- formatting -----------------------------------------------------------
function num(x, digits) {
  const s = x.toFixed(digits);
  return LANG === 'ru' ? s.replace('.', ',') : s;
}

function fmtSize(bytes) {
  const u = LANG === 'ru' ? ['Б', 'КБ', 'МБ', 'ГБ'] : ['B', 'KB', 'MB', 'GB'];
  if (bytes >= 1073741824) return num(bytes / 1073741824, 1) + ' ' + u[3];
  if (bytes >= 1048576) return Math.round(bytes / 1048576) + ' ' + u[2];
  if (bytes >= 1024) return Math.round(bytes / 1024) + ' ' + u[1];
  return bytes + ' ' + u[0];
}

function fmtRate(bps) {
  const mb = bps / 1048576;
  return (mb >= 10 ? mb.toFixed(0) : num(mb, 1)) + (LANG === 'ru' ? ' МБ/с' : ' MB/s');
}

function fmtEta(sec) {
  if (!isFinite(sec)) return '';
  if (sec >= 3600) return t('{0} ч {1} мин', Math.floor(sec / 3600), Math.floor(sec % 3600 / 60));
  if (sec >= 60) return t('{0} мин {1} с', Math.floor(sec / 60), Math.round(sec % 60));
  return t('{0} с', Math.max(1, Math.round(sec)));
}

function hhmm(ts) {
  const d = new Date(ts * 1000);
  return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}

function dayStart(d) { return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime(); }

function dayLabel(ts) {
  const today = dayStart(new Date());
  const day = dayStart(new Date(ts * 1000));
  if (day === today) return t('Сегодня');
  if (day === today - 86400000) return t('Вчера');
  return new Date(ts * 1000).toLocaleDateString(LANG === 'ru' ? 'ru-RU' : 'en-US', { day: 'numeric', month: 'long' });
}

function ago(ts) {
  const s = Date.now() / 1000 - ts;
  if (s < 60) return t('только что');
  if (s < 3600) return t('{0} мин назад', Math.floor(s / 60));
  if (dayLabel(ts) === t('Сегодня')) return t('{0} ч назад', Math.floor(s / 3600));
  if (dayLabel(ts) === t('Вчера')) return t('вчера');
  return dayLabel(ts);
}

function greeting() {
  const hr = new Date().getHours();
  if (hr < 5) return t('Доброй ночи!');
  if (hr < 12) return t('Доброе утро!');
  if (hr < 18) return t('Добрый день!');
  return t('Добрый вечер!');
}

function durationText(sec) {
  const hr = Math.floor(sec / 3600), m = Math.floor(sec % 3600 / 60);
  return hr ? t('{0} ч {1} мин', hr, String(m).padStart(2, '0')) : t('{0} мин', Math.max(1, m));
}

// ---- shared bits of markup ------------------------------------------------
const segS = on => ({ bg: on ? 'var(--surf)' : 'transparent', color: on ? 'var(--tx)' : 'var(--tx2)', shadow: on ? '0 1px 2px rgba(0,0,0,.18)' : 'none' });
const chipS = (on, c, tint) => ({ bg: on ? tint : 'transparent', bd: on ? 'transparent' : 'var(--line)', color: on ? c : 'var(--tx2)' });

function Toggle({ on, color, onClick }) {
  return html`<button onClick=${onClick} style="width:36px;height:20px;border-radius:999px;background:${on ? (color || 'var(--acc)') : 'var(--tgOff)'};position:relative;flex:none;transition:background .15s"><span style="position:absolute;top:2px;left:${on ? 18 : 2}px;width:16px;height:16px;border-radius:50%;background:#fff;transition:left .15s;box-shadow:0 1px 2px rgba(0,0,0,.25)"></span></button>`;
}

function Seg({ items, value, onChange, height = 28, pad = 12, radius = 9, grow = false }) {
  return html`<div style="display:flex;padding:3px;border-radius:${radius}px;background:var(--surf2);gap:2px;${grow ? '' : 'align-self:flex-start'}">
    ${items.map(it => {
      const s = segS(value === it.id);
      return html`<button key=${it.id} onClick=${() => onChange(it.id)} style="${grow ? 'flex:1;' : ''}height:${height}px;padding:0 ${pad}px;border-radius:${radius - 2}px;background:${s.bg};color:${s.color};box-shadow:${s.shadow};font-size:12px;font-weight:700;display:flex;align-items:center;justify-content:center;gap:6px">${it.icon ? html`<i style="font-size:16px">${it.icon}</i>` : null}${it.label}${it.count !== undefined ? html`<span style="font-size:11px;color:var(--tx3)">${it.count}</span>` : null}</button>`;
    })}
  </div>`;
}

function Chip({ on, color, tint, icon, label, onClick, height = 28 }) {
  const c = chipS(on, color, tint);
  return html`<button onClick=${onClick} style="height:${height}px;padding:0 10px 0 7px;border-radius:999px;white-space:nowrap;flex:none;border:1px solid ${c.bd};background:${c.bg};color:${c.color};display:flex;align-items:center;gap:5px;font-size:12px;font-weight:700"><i style="font-size:16px">${icon}</i>${label}</button>`;
}

function IconBtn({ icon, title, onClick, size = 30, iconSize = 18, danger = false }) {
  return html`<button onClick=${onClick} title=${title} style="width:${size}px;height:${size}px;border-radius:7px;display:flex;align-items:center;justify-content:center;color:var(--tx2)" style-hover=${danger ? 'background:var(--badT);color:var(--bad)' : 'background:var(--hover)'}><i style="font-size:${iconSize}px">${icon}</i></button>`;
}

function PageHead({ title, sub, children }) {
  return html`<div style="display:flex;align-items:flex-end;justify-content:space-between;gap:16px">
    <div>
      <div style="font-size:22px;font-weight:800;letter-spacing:-0.01em">${title}</div>
      <div style="font-size:13px;color:var(--tx2);margin-top:4px">${sub}</div>
    </div>
    ${children ? html`<div style="display:flex;align-items:center;gap:8px">${children}</div>` : null}
  </div>`;
}

const btnGhost = 'height:34px;padding:0 12px;border-radius:7px;background:var(--surf);border:1px solid var(--line);font-weight:600;display:flex;align-items:center;gap:6px';

function QR({ text, size }) {
  const svg = React.useMemo(() => {
    if (!text) return '';
    const q = qrcode(0, 'M');
    q.addData(text);
    q.make();
    return q.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
  }, [text]);
  return html`<div class="qr" style="width:${size}px;height:${size}px;border-radius:10px;overflow:hidden;background:#fff;padding:6px" dangerouslySetInnerHTML=${{ __html: svg }}></div>`;
}

const ScanDot = () => html`<span style="width:8px;height:8px;border-radius:50%;background:var(--ok);display:inline-block;animation:ldPulse 1.6s ease-out infinite"></span>`;

const FC = {
  files: ['var(--files)', 'var(--filesT)', 'description'],
  clip: ['var(--clip)', 'var(--clipT)', 'content_paste'],
  audio: ['var(--audio)', 'var(--audioT)', 'graphic_eq'],
};
