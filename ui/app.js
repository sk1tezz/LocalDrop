/* LocalDrop UI: screens ported from interface/LocalDrop.dc.html and wired to the backend. */
'use strict';

const kindIcon = { video: 'movie', folder: 'folder', doc: 'description', archive: 'folder_zip', image: 'image', audio: 'music_note', file: 'draft' };
const EXT_KIND = { video: 'mp4 mov mkv avi webm m4v wmv', image: 'png jpg jpeg gif webp heic bmp tif tiff svg', audio: 'mp3 wav flac aac ogg m4a opus', archive: 'zip rar 7z tar gz bz2 xz iso dmg', doc: 'pdf doc docx txt xls xlsx ppt pptx odt rtf md csv' };
function kindOf(name) {
  const ext = (name.includes('.') ? name.split('.').pop() : '').toLowerCase();
  for (const k in EXT_KIND) if (EXT_KIND[k].split(' ').includes(ext)) return k;
  return ext ? 'file' : 'folder';
}

// ---- derived views --------------------------------------------------------
function makeView(s, ui) {
  const me = s.me || {};
  const devById = {};
  s.devices.forEach(d => { devById[d.id] = d; });
  const dev = id => devById[id] || (id === 'me' || id === me.id
    ? { name: t('Этот ПК'), icon: 'computer', os: '', ip: '' }
    : { name: t('Неизвестное устройство'), icon: 'devices_other', os: '', ip: '' });
  const online = s.devices.filter(d => d.online);
  const paired = s.devices.filter(d => d.paired);
  const trusted = s.devices.filter(d => d.trusted);
  const sendable = online.filter(d => d.paired);
  return { me, dev, online, paired, trusted, sendable };
}

function transferView(tr, v) {
  const d = v.dev(tr.dev), st = tr.status;
  const pct = tr.size ? Math.min(100, tr.done / tr.size * 100) : (st === 'done' ? 100 : 0);
  const speed = tr.speed || 0;
  const labels = {
    active: speed ? fmtRate(speed) : t('соединение…'), paused: t('на паузе'), queued: t('в очереди'),
    waiting: t('ожидает подтверждения'), ask: t('хочет отправить вам'), offline: t('ждёт устройство'),
    error: tr.error || t('ошибка'), declined: t('отклонено'), cancelled: t('отменено'), done: t('готово'),
  };
  return {
    ...tr, d, pct, icon: kindIcon[tr.kind] || 'draft', devName: d.name,
    dirIcon: tr.dir === 'out' ? 'arrow_upward' : 'arrow_downward',
    meta: (tr.kind === 'folder' ? npl(tr.files, 'file') + ' · ' : '') + fmtSize(tr.size),
    prog: fmtSize(tr.done) + t(' из ') + fmtSize(tr.size),
    rate: labels[st] || st,
    etaText: st === 'active' && speed ? '· ' + t('осталось') + ' ' + fmtEta((tr.size - tr.done) / speed) : '',
    bar: st === 'active' ? 'var(--files)' : st === 'error' ? 'var(--bad)' : 'var(--tx3)',
    isFinal: ['done', 'error', 'declined', 'cancelled'].includes(st),
  };
}

function clipTitle(c) {
  if (c.type !== 'image') return c.text;
  const d = new Date(c.ts * 1000);
  return t('Изображение') + ' · ' + d.toLocaleDateString(LANG === 'ru' ? 'ru-RU' : 'en-US') + ' ' + hhmm(c.ts);
}

function histView(h, v) {
  const d = v.dev(h.dev), f = FC[h.type] || FC.files;
  return {
    ...h, devName: d.name, color: h.err ? 'var(--bad)' : f[0], tint: h.err ? 'var(--badT)' : f[1],
    icon: h.err ? 'error' : (h.type === 'files' ? kindIcon[kindOf(h.title)] : f[2]), subColor: h.err ? 'var(--bad)' : 'var(--tx3)',
    sub: subText(h.sub), title: h.type === 'audio' ? t(h.title) : h.title,
    dirIcon: h.dir === 'out' ? 'arrow_upward' : 'arrow_downward', time: hhmm(h.ts),
  };
}

// ---- window chrome --------------------------------------------------------
function TitleBar({ me }) {
  const isMac = me.kind === 'macos';
  if (isMac) {
    return html`<div class="pywebview-drag-region" style="height:40px;flex:none;display:flex;align-items:center;background:var(--side);border-bottom:1px solid var(--line2);user-select:none">
      <div style="display:flex;gap:8px;padding:0 16px;width:224px">
        <button onClick=${() => api('win', 'close')} title=${t('Закрыть')} style="width:12px;height:12px;border-radius:50%;background:#FF5F57"></button>
        <button onClick=${() => api('win', 'minimize')} title=${t('Свернуть')} style="width:12px;height:12px;border-radius:50%;background:#FEBC2E"></button>
        <button onClick=${() => api('win', 'maximize')} title=${t('Развернуть')} style="width:12px;height:12px;border-radius:50%;background:#28C840"></button>
      </div>
      <div style="flex:1;text-align:center;font-size:12px;font-weight:700;color:var(--tx2);margin-right:224px">LocalDrop</div>
    </div>`;
  }
  return html`<div style="height:40px;flex:none;display:flex;align-items:center;background:var(--side);border-bottom:1px solid var(--line2);user-select:none">
    <div class="pywebview-drag-region" onDoubleClick=${() => api('win', 'maximize')} style="display:flex;align-items:center;gap:9px;padding:0 14px;flex:1;height:100%">
      <div style="width:18px;height:18px;border-radius:5px;background:var(--acc);display:flex;align-items:center;justify-content:center"><i style="font-size:13px;color:var(--onAcc)">sync_alt</i></div>
      <div style="font-size:12px;font-weight:700;color:var(--tx2)">LocalDrop</div>
    </div>
    <div style="display:flex;height:100%">
      <button onClick=${() => api('win', 'minimize')} title=${t('Свернуть')} style="width:46px;height:100%;display:flex;align-items:center;justify-content:center;color:var(--tx2)" style-hover="background:var(--hover)"><i style="font-size:16px">remove</i></button>
      <button onClick=${() => api('win', 'maximize')} title=${t('Развернуть')} style="width:46px;height:100%;display:flex;align-items:center;justify-content:center;color:var(--tx2)" style-hover="background:var(--hover)"><i style="font-size:14px">crop_square</i></button>
      <button onClick=${() => api('win', 'close')} title=${t('Закрыть')} style="width:46px;height:100%;display:flex;align-items:center;justify-content:center;color:var(--tx2)" style-hover="background:#E81123;color:#fff"><i style="font-size:17px">close</i></button>
    </div>
  </div>`;
}

function Sidebar({ s, ui, go, v, themeEff }) {
  const activeCount = s.transfers.filter(tr => !transferView(tr, v).isFinal).length;
  const nav = [
    ['home', 'home', t('Главная'), 'var(--acc)'], ['devices', 'devices', t('Устройства'), 'var(--acc)'],
    ['files', 'folder_open', t('Файлы'), 'var(--files)'], ['clip', 'content_paste', t('Буфер обмена'), 'var(--clip)'],
    ['audio', 'graphic_eq', t('Аудио'), 'var(--audio)'], ['history', 'history', t('История'), 'var(--tx2)'],
  ];
  const setTheme = th => api('settings', { theme: th });
  return html`<div style="width:224px;flex:none;background:var(--side);border-right:1px solid var(--line2);display:flex;flex-direction:column;padding:14px 12px;gap:3px">
    <div style="display:flex;align-items:center;gap:10px;padding:2px 6px 16px">
      <div style="width:32px;height:32px;border-radius:8px;background:var(--acc);display:flex;align-items:center;justify-content:center"><i style="font-size:20px;color:var(--onAcc)">sync_alt</i></div>
      <div>
        <div style="font-size:15px;font-weight:800;letter-spacing:-0.01em">LocalDrop</div>
        <div style="font-size:11px;color:var(--tx3);font-weight:600">${t('Локальная сеть')}</div>
      </div>
    </div>
    ${nav.map(([id, icon, label, ic]) => {
      const on = ui.screen === id;
      const badge = id === 'files' && activeCount ? String(activeCount) : id === 'audio' && s.audio.on ? t('вкл') : id === 'audio' && s.audio.incoming ? t('приём') : '';
      return html`<button key=${id} onClick=${() => go(id)} style="height:36px;border-radius:8px;padding:0 10px;display:flex;align-items:center;gap:11px;background:${on ? 'var(--surf2)' : 'transparent'};color:${on ? 'var(--tx)' : 'var(--tx2)'};font-size:13px;font-weight:${on ? 700 : 600};text-align:left" style-hover="background:var(--surf2)">
        <i style="color:${ic}">${icon}</i>
        <span style="flex:1">${label}</span>
        ${badge ? html`<span style="font-size:10px;font-weight:800;padding:2px 6px;border-radius:999px;background:${id === 'files' ? 'var(--filesT)' : 'var(--audioT)'};color:${id === 'files' ? 'var(--files)' : 'var(--audio)'}">${badge}</span>` : null}
      </button>`;
    })}
    <div style="flex:1"></div>
    <div style="background:var(--surf);border:1px solid var(--line2);border-radius:10px;padding:12px;display:flex;flex-direction:column;gap:6px;margin-bottom:8px">
      <div style="font-size:11px;color:var(--tx3);font-weight:600">${t('Это устройство')}</div>
      <div style="font-size:13px;font-weight:700;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${s.settings.device_name}</div>
      <div style="display:flex;align-items:center;gap:6px;font-size:11px;color:var(--tx2)">
        <span style="width:7px;height:7px;border-radius:50%;background:${s.me.ip ? 'var(--ok)' : 'var(--bad)'}"></span>
        <span style="font-family:'JetBrains Mono',monospace">${s.me.ip || t('нет сети')}</span>
      </div>
    </div>
    <button onClick=${() => go('settings')} style="height:36px;border-radius:8px;padding:0 10px;display:flex;align-items:center;gap:11px;background:${ui.screen === 'settings' ? 'var(--surf2)' : 'transparent'};color:${ui.screen === 'settings' ? 'var(--tx)' : 'var(--tx2)'};font-size:13px;font-weight:600" style-hover="background:var(--surf2)"><i style="color:var(--tx2)">settings</i>${t('Настройки')}</button>
    <div style="display:flex;padding:3px;border-radius:8px;background:var(--surf2);gap:2px;margin-top:6px">
      <button onClick=${() => setTheme('light')} style="flex:1;height:26px;border-radius:6px;display:flex;align-items:center;justify-content:center;gap:6px;background:${themeEff === 'light' ? 'var(--surf)' : 'transparent'};color:${themeEff === 'light' ? 'var(--tx)' : 'var(--tx3)'};font-size:12px;font-weight:700"><i style="font-size:16px">light_mode</i>${t('Светлая')}</button>
      <button onClick=${() => setTheme('dark')} style="flex:1;height:26px;border-radius:6px;display:flex;align-items:center;justify-content:center;gap:6px;background:${themeEff === 'dark' ? 'var(--surf)' : 'transparent'};color:${themeEff === 'dark' ? 'var(--tx)' : 'var(--tx3)'};font-size:12px;font-weight:700"><i style="font-size:16px">dark_mode</i>${t('Тёмная')}</button>
    </div>
  </div>`;
}

// ---- small shared views ---------------------------------------------------
function DropZone({ ui, setUi, height, big, title, onPick }) {
  const border = ui.drag ? 'var(--files)' : 'var(--line)', bg = ui.drag ? 'var(--filesT)' : 'transparent';
  const over = e => { e.preventDefault(); if (!ui.drag) setUi({ drag: true }); };
  if (!big) {
    return html`<div onDragOver=${over} onDragLeave=${() => setUi({ drag: false })} onDrop=${e => { e.preventDefault(); setUi({ drag: false }); }} onClick=${onPick} style="border:1.5px dashed ${border};background:${bg};border-radius:8px;height:${height}px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;font-size:12px;color:var(--tx2);cursor:pointer;transition:all .15s"><i style="color:var(--files)">upload</i>${title}</div>`;
  }
  return html`<div onDragOver=${over} onDragLeave=${() => setUi({ drag: false })} onDrop=${e => { e.preventDefault(); setUi({ drag: false }); }} style="border:1.5px dashed ${border};background:${bg};border-radius:12px;height:${height}px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;transition:all .15s">
    <i style="font-size:30px;color:var(--files)">upload</i>
    <div style="font-size:14px;font-weight:700">${title}</div>
    <div style="font-size:12px;color:var(--tx3)">${t('Можно сразу несколько файлов или целую папку')}</div>
  </div>`;
}

function HistRow({ h, compact }) {
  if (compact) {
    return html`<div style="display:flex;align-items:center;gap:12px;padding:9px 16px">
      <div style="width:32px;height:32px;border-radius:8px;background:${h.tint};display:flex;align-items:center;justify-content:center"><i style="font-size:18px;color:${h.color}">${h.icon}</i></div>
      <div style="flex:1;min-width:0"><div style="font-size:13px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${h.title}</div><div style="font-size:12px;color:${h.subColor}">${h.sub}</div></div>
      <div style="display:flex;align-items:center;gap:5px;font-size:12px;color:var(--tx2)"><i style="font-size:15px">${h.dirIcon}</i>${h.devName}</div>
      <div style="width:46px;text-align:right;font-size:12px;color:var(--tx3);font-family:'JetBrains Mono',monospace">${h.time}</div>
    </div>`;
  }
  const action = h.err ? ['refresh', t('Повторить')] : h.type === 'files' ? ['folder_open', t('Показать в папке')] : h.type === 'clip' ? ['content_copy', t('Скопировать')] : null;
  return html`<div style="display:grid;grid-template-columns:32px minmax(0,1fr) 180px 52px 30px;gap:14px;align-items:center;padding:10px 16px;border-bottom:1px solid var(--line2)" style-hover="background:var(--surf2)">
    <div style="width:32px;height:32px;border-radius:8px;background:${h.tint};display:flex;align-items:center;justify-content:center"><i style="font-size:18px;color:${h.color}">${h.icon}</i></div>
    <div style="min-width:0"><div style="font-size:13px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${h.title}</div><div style="font-size:12px;color:${h.subColor}">${h.sub}</div></div>
    <div style="display:flex;align-items:center;gap:6px;font-size:12px;color:var(--tx2);min-width:0"><i style="font-size:15px">${h.dirIcon}</i><span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${h.devName}</span></div>
    <div style="text-align:right;font-size:12px;color:var(--tx3);font-family:'JetBrains Mono',monospace">${h.time}</div>
    ${action ? html`<${IconBtn} icon=${action[0]} title=${action[1]} onClick=${() => api('history_action', h.id)} />` : html`<span></span>`}
  </div>`;
}

const qualityInfo = () => ({
  low: [t('Низкая задержка'), '96 ' + t('кбит/с'), t('≈ 25 мс · Opus 96 кбит/с — для видео и игр')],
  balance: [t('Баланс'), '192 ' + t('кбит/с'), t('≈ 60 мс · Opus 192 кбит/с — подходит почти всем')],
  hq: [t('Высокое качество'), '1536 ' + t('кбит/с'), t('≈ 120 мс · без сжатия — для музыки')],
});

function audioStateText(s, v) {
  const targets = (s.settings.audio.targets || []).filter(a => a.on);
  if (!s.audio.on) return t('Трансляция выключена');
  if (!targets.length) return t('Нет выбранных устройств');
  return t('Транслируется на {0}', npl(targets.length, 'device'));
}

function IncomingAudio({ s, v }) {
  const inc = s.audio.incoming;
  if (!inc) return null;
  return html`<div style="display:flex;align-items:center;gap:14px;padding:12px 16px;border-radius:10px;background:var(--audioT);border:1px solid var(--line2)">
    <div style="width:34px;height:34px;border-radius:8px;background:var(--surf);display:flex;align-items:center;justify-content:center"><i style="color:var(--audio)">speaker</i></div>
    <div style="flex:1;min-width:0"><div style="font-size:13px;font-weight:700">${t('Играет звук с {0}', v.dev(inc.dev).name)}</div><div style="font-size:12px;color:var(--tx2)">${inc.out || t('По умолчанию')} · ${inc.vol}%</div></div>
    <button onClick=${() => api('audio_incoming_stop')} style="height:30px;padding:0 12px;border-radius:7px;background:var(--surf);font-weight:700;font-size:12px" style-hover="background:var(--hover)">${t('Отключить')}</button>
  </div>`;
}

// ---- screens --------------------------------------------------------------
function Home({ s, ui, setUi, go, v, sendTo }) {
  const trs = s.transfers.map(tr => transferView(tr, v));
  const active = trs.filter(x => !x.isFinal);
  const running = active.filter(x => x.status === 'active');
  const speed = running.reduce((a, x) => a + (x.speed || 0), 0);
  const cur = running[0] || active[0];
  const clipCount = (s.settings.clip_devices || []).filter(id => v.dev(id).paired).length;
  const last = s.clips[0];
  const targetsOn = (s.settings.audio.targets || []).filter(a => a.on);
  const q = qualityInfo()[s.settings.audio.quality];
  return html`<div style="display:flex;flex-direction:column;gap:22px">
    <${PageHead} title=${greeting()} sub=${t('{0} в сети', npl(v.online.length, 'device')) + ' · ' + t('всё работает напрямую, без облака')}>
      <button onClick=${() => { setUi({ devTab: 'add' }); go('devices'); }} style=${btnGhost} style-hover="background:var(--hover)"><i style="font-size:18px">add</i>${t('Добавить устройство')}</button>
    <//>
    <div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px">
      <div style="background:var(--surf);border:1px solid var(--line2);border-radius:10px;padding:16px;display:flex;flex-direction:column;gap:14px">
        <div style="display:flex;align-items:center;gap:10px">
          <div style="width:34px;height:34px;border-radius:8px;background:var(--filesT);display:flex;align-items:center;justify-content:center"><i style="color:var(--files)">folder_open</i></div>
          <div style="flex:1;min-width:0"><div style="font-size:14px;font-weight:700">${t('Файлы')}</div><div style="font-size:12px;color:var(--tx2)">${active.length ? t('{0} в очереди', active.length) + (speed ? ' · ' + fmtRate(speed) : '') : t('Нет активных передач')}</div></div>
          <${IconBtn} icon="arrow_forward" onClick=${() => go('files')} />
        </div>
        <${DropZone} ui=${ui} setUi=${setUi} height=${84} title=${t('Перетащите файлы или папки')} onPick=${() => sendTo('files')} />
        ${cur ? html`<div style="display:flex;flex-direction:column;gap:7px">
          <div style="display:flex;justify-content:space-between;gap:8px;font-size:12px"><span style="font-weight:700;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${cur.name}</span><span style="font-family:'JetBrains Mono',monospace;color:var(--tx2);flex:none">${cur.pct.toFixed(0)}%</span></div>
          <div style="height:5px;border-radius:3px;background:var(--surf2);overflow:hidden"><div style="height:100%;width:${cur.pct.toFixed(1)}%;background:var(--files);border-radius:3px;transition:width .5s linear"></div></div>
          <div style="font-size:11px;color:var(--tx3)">${cur.rate} ${cur.etaText}</div>
        </div>` : null}
      </div>
      <div style="background:var(--surf);border:1px solid var(--line2);border-radius:10px;padding:16px;display:flex;flex-direction:column;gap:14px">
        <div style="display:flex;align-items:center;gap:10px">
          <div style="width:34px;height:34px;border-radius:8px;background:var(--clipT);display:flex;align-items:center;justify-content:center"><i style="color:var(--clip)">content_paste</i></div>
          <div style="flex:1;min-width:0"><div style="font-size:14px;font-weight:700">${t('Буфер обмена')}</div><div style="font-size:12px;color:var(--tx2)">${s.settings.clip_auto ? t('Авто · {0}', npl(clipCount, 'device')) : t('Только вручную')}</div></div>
          <${Toggle} on=${s.settings.clip_auto} color="var(--clip)" onClick=${() => api('settings', { clip_auto: !s.settings.clip_auto })} />
        </div>
        <div onClick=${() => go('clip')} style="background:var(--surf2);border-radius:8px;padding:12px;height:84px;display:flex;flex-direction:column;gap:6px;cursor:pointer">
          <div style="font-size:11px;color:var(--tx3);font-weight:600">${t('Последнее')} · ${last ? (last.dev === 'me' ? t('Этот ПК') : v.dev(last.dev).name) : '—'}</div>
          <div style="font-size:13px;font-weight:600;line-height:1.4;overflow:hidden;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;word-break:break-word">${last ? clipTitle(last) : t('Буфер пуст')}</div>
        </div>
        <div style="display:flex;gap:8px">
          <button onClick=${() => api('clip_send_current')} style="flex:1;height:32px;border-radius:7px;background:var(--clipT);color:var(--clip);font-weight:700;font-size:12px;display:flex;align-items:center;justify-content:center;gap:6px" style-hover="filter:brightness(1.15)"><i style="font-size:17px">send</i>${t('Отправить сейчас')}</button>
          <button onClick=${() => go('clip')} style="height:32px;padding:0 12px;border-radius:7px;background:var(--surf2);font-weight:600;font-size:12px" style-hover="background:var(--hover)">${t('История')}</button>
        </div>
      </div>
      <div style="background:var(--surf);border:1px solid var(--line2);border-radius:10px;padding:16px;display:flex;flex-direction:column;gap:14px">
        <div style="display:flex;align-items:center;gap:10px">
          <div style="width:34px;height:34px;border-radius:8px;background:var(--audioT);display:flex;align-items:center;justify-content:center"><i style="color:var(--audio)">graphic_eq</i></div>
          <div style="flex:1;min-width:0"><div style="font-size:14px;font-weight:700">${t('Аудио')}</div><div style="font-size:12px;color:var(--tx2)">${audioStateText(s, v)}</div></div>
          <${Toggle} on=${s.audio.on} color="var(--audio)" onClick=${() => api('audio_toggle')} />
        </div>
        <div onClick=${() => go('audio')} style="background:var(--surf2);border-radius:8px;padding:12px;height:84px;display:flex;flex-direction:column;gap:8px;cursor:pointer">
          ${s.audio.incoming
            ? html`<div style="font-size:11px;color:var(--tx3);font-weight:600">${t('Сейчас играет здесь')}</div>
                <span style="display:flex;align-items:center;gap:5px;font-size:12px;font-weight:600"><i style="font-size:15px;color:var(--audio)">speaker</i>${t('Звук с {0}', v.dev(s.audio.incoming.dev).name)}</span>`
            : html`<div style="font-size:11px;color:var(--tx3);font-weight:600">${t('Звук ПК играет на')}</div>
                <div style="display:flex;flex-wrap:wrap;gap:6px">
                  ${targetsOn.length ? targetsOn.map(a => html`<span key=${a.dev} style="display:flex;align-items:center;gap:5px;font-size:12px;font-weight:600;padding:3px 8px;border-radius:999px;background:var(--surf)"><i style="font-size:15px;color:var(--audio)">${v.dev(a.dev).icon}</i>${v.dev(a.dev).name}</span>`)
                    : html`<span style="font-size:12px;color:var(--tx3)">${t('Устройства не выбраны')}</span>`}
                </div>`}
        </div>
        <div style="display:flex;justify-content:space-between;align-items:center;font-size:12px;color:var(--tx2)">
          <span>${q[0]} · ${q[1]}</span>
          <span style="font-family:'JetBrains Mono',monospace">${s.audio.on && s.audio.latency ? s.audio.latency + ' ' + t('мс') : '—'}</span>
        </div>
      </div>
    </div>
    <div>
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px"><div style="font-size:14px;font-weight:700">${t('Устройства рядом')}</div><button onClick=${() => go('devices')} style="font-size:12px;font-weight:700;color:var(--acc)">${t('Все устройства')}</button></div>
      ${v.online.length ? html`<div style="display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:10px">
        ${v.online.slice(0, 6).map(d => html`<div key=${d.id} onClick=${() => d.paired ? (setUi({ target: d.id }), go('files')) : api('pair_request', d.id)} title=${d.paired ? t('Отправить файлы') : t('Подключить')} style="background:var(--surf);border:1px solid var(--line2);border-radius:10px;padding:14px 12px;display:flex;flex-direction:column;gap:10px;cursor:pointer;transition:border-color .15s" style-hover="border-color:var(--acc)">
          <div style="position:relative;width:28px"><i style="font-size:28px;color:var(--tx2)">${d.icon}</i><span style="position:absolute;right:-3px;bottom:0;width:9px;height:9px;border-radius:50%;background:${d.paired ? 'var(--ok)' : 'var(--acc)'};border:2px solid var(--surf)"></span></div>
          <div style="min-width:0"><div style="font-size:12px;font-weight:700;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${d.name}</div><div style="font-size:11px;color:var(--tx3);font-family:'JetBrains Mono',monospace;margin-top:2px">${d.ip}</div></div>
        </div>`)}
      </div>` : html`<div style="padding:22px;text-align:center;font-size:12px;color:var(--tx3);background:var(--surf);border:1px solid var(--line2);border-radius:10px">${t('Пока никого не видно. Запустите LocalDrop на другом компьютере в этой же сети.')}</div>`}
    </div>
    <div style="background:var(--surf);border:1px solid var(--line2);border-radius:10px;padding:6px 0">
      <div style="display:flex;justify-content:space-between;align-items:center;padding:10px 16px 6px"><div style="font-size:14px;font-weight:700">${t('Недавнее')}</div><button onClick=${() => go('history')} style="font-size:12px;font-weight:700;color:var(--acc)">${t('Вся история')}</button></div>
      ${s.history.length ? s.history.slice(0, 4).map(h => html`<${HistRow} key=${h.id} h=${histView(h, v)} compact=${true} />`)
        : html`<div style="padding:14px 16px 16px;font-size:12px;color:var(--tx3)">${t('Здесь пока ничего нет')}</div>`}
    </div>
  </div>`;
}

function Devices({ s, ui, setUi, go, v }) {
  const qq = (ui.q || '').trim().toLowerCase();
  const match = d => !qq || (d.name + ' ' + d.ip + ' ' + d.os).toLowerCase().includes(qq);
  const list = (ui.devTab === 'trusted' ? v.paired : s.devices).filter(match);
  const codeDigits = (ui.code || '').replace(/\D/g, '');
  return html`<div style="display:flex;flex-direction:column;gap:18px">
    <${PageHead} title=${t('Устройства')} sub=${t('{0} в сети', npl(v.online.length, 'device')) + ' · ' + npl(v.trusted.length, 'trusted')}>
      <div style="display:flex;align-items:center;gap:8px;font-size:12px;color:var(--tx2);font-weight:600"><${ScanDot} />${s.scanning ? t('Поиск…') : t('Автопоиск включён')}</div>
      <button onClick=${() => api('rescan')} style=${btnGhost} style-hover="background:var(--hover)"><i style="font-size:18px">refresh</i>${t('Обновить')}</button>
    <//>
    <${Seg} value=${ui.devTab} onChange=${id => setUi({ devTab: id })} height=${30} pad=${14}
      items=${[{ id: 'nearby', label: t('Все рядом'), count: String(s.devices.length) }, { id: 'trusted', label: t('Сопряжённые'), count: String(v.paired.length) }, { id: 'add', label: t('Добавить'), count: '' }]} />
    ${ui.devTab !== 'add' ? html`
      <div style="height:36px;border-radius:8px;background:var(--surf);border:1px solid var(--line);display:flex;align-items:center;gap:8px;padding:0 12px"><i style="font-size:18px;color:var(--tx3)">search</i><input value=${ui.q} onInput=${e => setUi({ q: e.target.value })} placeholder=${t('Поиск по имени, системе или IP-адресу')} style="flex:1;min-width:0;border:0;background:none;font-size:13px" /></div>
      <div style="background:var(--surf);border:1px solid var(--line2);border-radius:10px;overflow:hidden">
        <div style="display:grid;grid-template-columns:minmax(0,1fr) 140px 120px 260px;gap:12px;padding:10px 16px;font-size:11px;font-weight:700;color:var(--tx3);border-bottom:1px solid var(--line2)"><span>${t('Устройство')}</span><span>${t('IP-адрес')}</span><span>${t('Статус')}</span><span></span></div>
        ${list.map(d => html`<div key=${d.id} style="display:grid;grid-template-columns:minmax(0,1fr) 140px 120px 260px;gap:12px;align-items:center;padding:10px 16px;border-bottom:1px solid var(--line2)" style-hover="background:var(--surf2)">
          <div style="display:flex;align-items:center;gap:12px;min-width:0;opacity:${d.online ? 1 : 0.5}">
            <div style="width:34px;height:34px;border-radius:8px;background:var(--surf2);display:flex;align-items:center;justify-content:center"><i style="font-size:20px;color:var(--tx2)">${d.icon}</i></div>
            <div style="min-width:0"><div style="font-size:13px;font-weight:700;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${d.name}</div><div style="font-size:11px;color:var(--tx3)">${d.os}</div></div>
          </div>
          <span style="font-family:'JetBrains Mono',monospace;font-size:12px;color:var(--tx2)">${d.ip || '—'}</span>
          <span style="display:flex;align-items:center;gap:6px;font-size:12px;color:var(--tx2)"><span style="width:7px;height:7px;border-radius:50%;background:${d.online ? 'var(--ok)' : 'var(--tx3)'}"></span>${d.online ? t('В сети') : t('Не в сети')}</span>
          <div style="display:flex;align-items:center;justify-content:flex-end;gap:8px">
            <span style="font-size:11px;font-weight:700;padding:3px 8px;border-radius:999px;background:${d.paired ? 'var(--surf2)' : 'var(--accT)'};color:${d.paired ? 'var(--tx2)' : 'var(--acc)'}">${d.trusted ? t('Доверенное') : d.paired ? t('Сопряжено') : t('Новое')}</span>
            ${!d.paired && d.online ? html`<button onClick=${() => api('pair_request', d.id)} style="height:28px;padding:0 12px;border-radius:6px;background:var(--acc);color:var(--onAcc);font-size:12px;font-weight:700" style-hover="filter:brightness(1.08)">${t('Подключить')}</button>` : null}
            ${d.paired && d.online ? html`<button onClick=${() => { setUi({ target: d.id }); go('files'); }} style="height:28px;padding:0 12px;border-radius:6px;background:var(--surf2);font-size:12px;font-weight:700" style-hover="background:var(--hover)">${t('Отправить')}</button>` : null}
            ${d.paired && !d.trusted ? html`<${IconBtn} icon="verified_user" title=${t('Сделать доверенным')} size=${28} iconSize=${17} onClick=${() => api('set_trust', d.id, true)} />` : null}
            ${d.trusted ? html`<${IconBtn} icon="remove_moderator" title=${t('Удалить из доверенных')} size=${28} iconSize=${17} danger=${true} onClick=${() => api('set_trust', d.id, false)} />` : null}
            ${d.paired ? html`<${IconBtn} icon="link_off" title=${t('Забыть устройство')} size=${28} iconSize=${17} danger=${true} onClick=${() => api('forget', d.id)} />` : null}
          </div>
        </div>`)}
        ${!list.length ? html`<div style="padding:28px;text-align:center;font-size:13px;color:var(--tx3)">${t('Ничего не найдено. Попробуйте добавить устройство по IP-адресу.')}</div>` : null}
      </div>` : html`<${AddDevice} s=${s} ui=${ui} setUi=${setUi} codeDigits=${codeDigits} />`}
  </div>`;
}

function AddDevice({ s, ui, setUi, codeDigits }) {
  const card = 'background:var(--surf);border:1px solid var(--line2);border-radius:10px;padding:20px;display:flex;flex-direction:column;gap:14px';
  const ico = icon => html`<div style="width:38px;height:38px;border-radius:9px;background:var(--accT);display:flex;align-items:center;justify-content:center"><i style="color:var(--acc)">${icon}</i></div>`;
  const ipOk = /^\d{1,3}(\.\d{1,3}){3}$/.test((ui.ip || '').trim());
  const connectIp = () => { if (!ipOk) return; api('connect_ip', ui.ip.trim(), parseInt(ui.port, 10) || 53317); setUi({ ip: '' }); };
  const confirmCode = () => { if (codeDigits.length !== 6) return; api('pair_code', codeDigits); setUi({ code: '' }); };
  return html`<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px">
    <div style=${card}>
      ${ico('lan')}
      <div><div style="font-size:15px;font-weight:800">${t('По IP-адресу')}</div><div style="font-size:12px;color:var(--tx2);margin-top:4px;line-height:1.5">${t('Если устройство в другой подсети или не видно в списке')}</div></div>
      <div style="display:flex;flex-direction:column;gap:6px"><span style="font-size:11px;font-weight:700;color:var(--tx3)">${t('IP-адрес')}</span><input value=${ui.ip} onInput=${e => setUi({ ip: e.target.value })} onKeyDown=${e => e.key === 'Enter' && connectIp()} placeholder="192.168.1.50" style="height:36px;border-radius:7px;border:1px solid var(--line);background:var(--surf2);padding:0 10px;font-family:'JetBrains Mono',monospace;font-size:13px" /></div>
      <div style="display:flex;flex-direction:column;gap:6px"><span style="font-size:11px;font-weight:700;color:var(--tx3)">${t('Порт')}</span><input value=${ui.port} onInput=${e => setUi({ port: e.target.value.replace(/\D/g, '') })} style="height:36px;border-radius:7px;border:1px solid var(--line);background:var(--surf2);padding:0 10px;font-family:'JetBrains Mono',monospace;font-size:13px" /></div>
      <div style="flex:1"></div>
      <button onClick=${connectIp} style="height:36px;border-radius:7px;background:var(--acc);color:var(--onAcc);font-weight:700;opacity:${ipOk ? 1 : 0.5}" style-hover="filter:brightness(1.08)">${t('Подключиться')}</button>
    </div>
    <div style=${card}>
      ${ico('pin')}
      <div><div style="font-size:15px;font-weight:800">${t('Код сопряжения')}</div><div style="font-size:12px;color:var(--tx2);margin-top:4px;line-height:1.5">${t('Введите этот код на другом устройстве')}</div></div>
      <div style="background:var(--surf2);border-radius:8px;padding:14px;display:flex;align-items:center;justify-content:space-between">
        <span style="font-family:'JetBrains Mono',monospace;font-size:26px;font-weight:500;letter-spacing:.12em">${s.me.code || '— — —'}</span>
        <${IconBtn} icon="refresh" title=${t('Новый код')} onClick=${() => api('new_code')} />
      </div>
      <div style="font-size:11px;font-weight:700;color:var(--tx3)">${t('Или введите код другого устройства')}</div>
      <input value=${ui.code} onInput=${e => setUi({ code: e.target.value })} onKeyDown=${e => e.key === 'Enter' && confirmCode()} placeholder="000 000" maxlength="7" style="height:40px;border-radius:7px;border:1px solid var(--line);background:var(--surf2);padding:0 12px;font-family:'JetBrains Mono',monospace;font-size:18px;letter-spacing:.12em;text-align:center" />
      <button onClick=${confirmCode} style="height:36px;border-radius:7px;background:var(--acc);color:var(--onAcc);font-weight:700;opacity:${codeDigits.length === 6 ? 1 : 0.5}" style-hover="filter:brightness(1.08)">${t('Подтвердить')}</button>
    </div>
    <div style=${card}>
      ${ico('qr_code_2')}
      <div><div style="font-size:15px;font-weight:800">${t('QR-код')}</div><div style="font-size:12px;color:var(--tx2);margin-top:4px;line-height:1.5">${t('Отсканируйте камерой ноутбука в LocalDrop на другом устройстве')}</div></div>
      <div style="align-self:center"><${QR} text=${s.me.qr} size=${170} /></div>
      <div style="font-size:11px;color:var(--tx3);text-align:center;line-height:1.5">${t('Код обновляется каждые 60 секунд')}</div>
      <button onClick=${() => api('qr_scan_start')} style="height:36px;border-radius:7px;background:var(--surf2);border:1px solid var(--line);font-weight:700;display:flex;align-items:center;justify-content:center;gap:6px" style-hover="background:var(--hover)"><i style="font-size:18px">photo_camera</i>${t('Сканировать QR')}</button>
    </div>
  </div>`;
}

function Files({ s, ui, setUi, v, sendTo }) {
  const trs = s.transfers.map(tr => transferView(tr, v));
  const active = trs.filter(x => !x.isFinal), done = trs.filter(x => x.isFinal).reverse();
  const running = active.filter(x => x.status === 'active');
  const anyRunning = running.length > 0;
  const speed = running.reduce((a, x) => a + (x.speed || 0), 0);
  const target = ui.target && v.dev(ui.target);
  const dropTitle = ui.drag ? t('Отпустите, чтобы отправить') : target ? t('Перетащите файлы или папки на {0}', target.name) : t('Выберите получателя выше');
  return html`<div style="display:flex;flex-direction:column;gap:18px">
    <${PageHead} title=${t('Перенос файлов')} sub=${t('Файлы и папки любого размера — напрямую по локальной сети')}>
      <button onClick=${() => sendTo('folder')} style=${btnGhost} style-hover="background:var(--hover)"><i style="font-size:18px">create_new_folder</i>${t('Выбрать папку')}</button>
      <button onClick=${() => sendTo('files')} style="height:34px;padding:0 14px;border-radius:7px;background:var(--files);color:var(--onAcc);font-weight:700;display:flex;align-items:center;gap:6px" style-hover="filter:brightness(1.08)"><i style="font-size:18px">note_add</i>${t('Выбрать файлы')}</button>
    <//>
    <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap">
      <span style="font-size:12px;font-weight:700;color:var(--tx3);margin-right:4px">${t('Кому:')}</span>
      ${v.sendable.length ? v.sendable.map(d => html`<${Chip} key=${d.id} on=${ui.target === d.id} color="var(--files)" tint="var(--filesT)" icon=${d.icon} label=${d.name} height=${30} onClick=${() => setUi({ target: d.id })} />`)
        : html`<span style="font-size:12px;color:var(--tx3)">${t('Нет сопряжённых устройств в сети')}</span>`}
    </div>
    <${DropZone} ui=${ui} setUi=${setUi} height=${130} big=${true} title=${dropTitle} />
    <div style="background:var(--surf);border:1px solid var(--line2);border-radius:10px">
      <div style="display:flex;align-items:center;justify-content:space-between;padding:14px 16px;border-bottom:1px solid var(--line2)">
        <div style="display:flex;align-items:baseline;gap:10px"><span style="font-size:14px;font-weight:700">${t('Очередь')}</span><span style="font-size:12px;color:var(--tx3)">${active.length ? t('{0} активн.', running.length) + (speed ? ' · ' + fmtRate(speed) : '') : ''}</span></div>
        ${active.length ? html`<button onClick=${() => api(anyRunning ? 'transfers_pause_all' : 'transfers_resume_all')} style="height:28px;padding:0 10px;border-radius:6px;background:var(--surf2);font-size:12px;font-weight:700;display:flex;align-items:center;gap:5px" style-hover="background:var(--hover)"><i style="font-size:16px">${anyRunning ? 'pause' : 'play_arrow'}</i>${anyRunning ? t('Пауза для всех') : t('Продолжить все')}</button>` : null}
      </div>
      ${active.map(x => html`<div key=${x.id} style="display:flex;align-items:center;gap:14px;padding:12px 16px;border-bottom:1px solid var(--line2)">
        <div style="width:38px;height:38px;border-radius:9px;background:var(--filesT);display:flex;align-items:center;justify-content:center"><i style="color:var(--files)">${x.icon}</i></div>
        <div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:6px">
          <div style="display:flex;align-items:center;gap:10px;font-size:12px">
            <span style="font-size:13px;font-weight:700;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${x.name}</span>
            <span style="display:flex;align-items:center;gap:3px;color:var(--tx3);flex:none"><i style="font-size:14px">${x.dirIcon}</i>${x.devName}</span>
            <span style="flex:1"></span>
            <span style="font-family:'JetBrains Mono',monospace;color:var(--tx2);flex:none">${x.prog}</span>
          </div>
          <div style="height:6px;border-radius:3px;background:var(--surf2);overflow:hidden"><div style="height:100%;width:${x.pct.toFixed(1)}%;background:${x.bar};border-radius:3px;transition:width .5s linear"></div></div>
          <div style="display:flex;justify-content:space-between;font-size:11px;color:var(--tx3)"><span>${x.meta}</span><span style="font-family:'JetBrains Mono',monospace">${x.pct.toFixed(0)}% · ${x.rate} ${x.etaText}</span></div>
        </div>
        <div style="display:flex;gap:4px">
          ${x.status === 'ask'
            ? html`<${IconBtn} icon="check" title=${t('Принять')} iconSize=${19} onClick=${() => api('offer_respond', x.id, true)} />
                   <${IconBtn} icon="close" title=${t('Отклонить')} iconSize=${19} danger=${true} onClick=${() => api('offer_respond', x.id, false)} />`
            : html`${['active', 'paused', 'queued', 'offline'].includes(x.status) ? html`<${IconBtn} icon=${x.status === 'paused' ? 'play_arrow' : 'pause'} title=${t('Пауза / продолжить')} iconSize=${19} onClick=${() => api('transfer_toggle', x.id)} />` : null}
                   <${IconBtn} icon="close" title=${t('Отменить')} iconSize=${19} danger=${true} onClick=${() => api('transfer_cancel', x.id)} />`}
        </div>
      </div>`)}
      ${!active.length ? html`<div style="padding:22px;text-align:center;font-size:12px;color:var(--tx3)">${t('Очередь пуста — перетащите файлы выше')}</div>` : null}
      ${done.length ? html`
        <div style="display:flex;align-items:center;justify-content:space-between;padding:12px 16px 4px"><span style="font-size:12px;font-weight:700;color:var(--tx3)">${t('Завершённые')}</span><button onClick=${() => api('transfers_clear_done')} style="font-size:12px;font-weight:700;color:var(--acc)">${t('Очистить')}</button></div>
        ${done.map(x => html`<div key=${x.id} style="display:flex;align-items:center;gap:14px;padding:9px 16px">
          <div style="width:30px;height:30px;border-radius:8px;background:var(--surf2);display:flex;align-items:center;justify-content:center"><i style="font-size:18px;color:var(--tx2)">${x.icon}</i></div>
          <div style="flex:1;min-width:0"><div style="font-size:13px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${x.name}</div><div style="font-size:11px;color:var(--tx3);display:flex;align-items:center;gap:3px"><i style="font-size:13px">${x.dirIcon}</i>${x.devName} · ${x.meta}</div></div>
          ${x.status === 'done'
            ? html`<span style="display:flex;align-items:center;gap:4px;font-size:12px;color:var(--ok);font-weight:700"><i style="font-size:16px">check_circle</i>${x.when ? hhmm(x.when) : ''}</span>`
            : html`<span style="display:flex;align-items:center;gap:4px;font-size:12px;color:var(--bad);font-weight:700;max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap"><i style="font-size:16px">error</i>${x.rate}</span>`}
          ${x.status === 'done' && x.path ? html`<${IconBtn} icon="folder_open" title=${t('Показать в папке')} onClick=${() => api('reveal', x.path)} />` : null}
          ${x.status === 'error' && x.dir === 'out' ? html`<${IconBtn} icon="refresh" title=${t('Повторить')} onClick=${() => api('transfer_retry', x.id)} />` : null}
        </div>`)}
        <div style="height:6px"></div>` : null}
    </div>
    <div style="display:flex;align-items:center;gap:12px;padding:12px 16px;border-radius:10px;background:var(--surf);border:1px solid var(--line2)">
      <i style="color:var(--tx2)">download</i>
      <div style="flex:1;min-width:0"><div style="font-size:12px;color:var(--tx3);font-weight:600">${t('Входящие файлы сохраняются в')}</div><div style="font-size:12px;font-family:'JetBrains Mono',monospace;margin-top:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${s.settings.save_dir}</div></div>
      <button onClick=${() => api('open_save_dir')} style="height:28px;padding:0 10px;border-radius:6px;background:var(--surf2);font-size:12px;font-weight:700" style-hover="background:var(--hover)">${t('Открыть')}</button>
      <button onClick=${() => api('change_save_dir')} style="height:28px;padding:0 10px;border-radius:6px;background:var(--surf2);font-size:12px;font-weight:700" style-hover="background:var(--hover)">${t('Изменить')}</button>
    </div>
  </div>`;
}

function Clipboard({ s, ui, setUi, v }) {
  const st = s.settings;
  const clipDevs = new Set(st.clip_devices || []);
  const types = st.clip_types || {};
  const toggleDev = id => api('settings', { clip_devices: clipDevs.has(id) ? [...clipDevs].filter(x => x !== id) : [...clipDevs, id] });
  const clips = s.clips.filter(c => ui.clipFilter === 'all' || c.type === ui.clipFilter);
  const typeInfo = { text: ['notes', t('Текст')], image: ['image', t('Изображение')] };
  return html`<div style="display:flex;flex-direction:column;gap:18px">
    <${PageHead} title=${t('Буфер обмена')} sub=${t('Копируйте на одном компьютере — вставляйте на другом')}>
      <button onClick=${() => api('clip_clear')} style=${btnGhost} style-hover="background:var(--hover)"><i style="font-size:18px">delete_sweep</i>${t('Очистить')}</button>
      <button onClick=${() => api('clip_send_current')} style="height:34px;padding:0 14px;border-radius:7px;background:var(--clip);color:var(--onAcc);font-weight:700;display:flex;align-items:center;gap:6px" style-hover="filter:brightness(1.08)"><i style="font-size:18px">send</i>${t('Отправить текущий буфер')}</button>
    <//>
    <div style="background:var(--surf);border:1px solid var(--line2);border-radius:10px;padding:16px;display:flex;flex-direction:column;gap:14px">
      <div style="display:flex;align-items:center;gap:12px">
        <${Toggle} on=${st.clip_auto} color="var(--clip)" onClick=${() => api('settings', { clip_auto: !st.clip_auto })} />
        <div style="flex:1"><div style="font-size:13px;font-weight:700">${t('Автосинхронизация')}</div><div style="font-size:12px;color:var(--tx2)">${t('Всё скопированное сразу появляется на выбранных устройствах')}</div></div>
      </div>
      <div style="border-top:1px solid var(--line2);padding-top:14px;display:grid;grid-template-columns:110px minmax(0,1fr);gap:12px 10px;align-items:center">
        <span style="font-size:12px;font-weight:700;color:var(--tx3)">${t('Устройства')}</span>
        <div style="display:flex;flex-wrap:wrap;gap:6px">
          ${v.paired.length ? v.paired.map(d => html`<${Chip} key=${d.id} on=${clipDevs.has(d.id)} color="var(--clip)" tint="var(--clipT)" icon=${clipDevs.has(d.id) ? 'check' : 'add'} label=${d.name} onClick=${() => toggleDev(d.id)} />`)
            : html`<span style="font-size:12px;color:var(--tx3)">${t('Сначала подключите устройство')}</span>`}
        </div>
        <span style="font-size:12px;font-weight:700;color:var(--tx3)">${t('Что передавать')}</span>
        <div style="display:flex;flex-wrap:wrap;gap:6px">
          ${[['text', t('Текст')], ['image', t('Изображения')]].map(([k, name]) => html`<${Chip} key=${k} on=${!!types[k]} color="var(--clip)" tint="var(--clipT)" icon=${types[k] ? 'check' : 'add'} label=${name} onClick=${() => api('settings', { clip_types: { [k]: !types[k] } })} />`)}
        </div>
      </div>
    </div>
    <div style="display:flex;align-items:center;justify-content:space-between">
      <${Seg} value=${ui.clipFilter} onChange=${id => setUi({ clipFilter: id })} items=${[{ id: 'all', label: t('Все') }, { id: 'text', label: t('Текст') }, { id: 'image', label: t('Изображения') }]} />
      <span style="font-size:12px;color:var(--tx3)">${npl(clips.length, 'element')}</span>
    </div>
    <div style="display:flex;flex-direction:column;gap:8px">
      ${clips.map(c => {
        const info = typeInfo[c.type] || typeInfo.text;
        const mono = c.type === 'text' && /^[\w.-]+\s|[{};=]|^\$ |==/.test(c.text) && !/\s{2}/.test(c.text) && c.text.length < 200 && !/[а-яё]/i.test(c.text);
        return html`<div key=${c.id} style="display:flex;align-items:center;gap:14px;padding:12px 14px;background:var(--surf);border:1px solid var(--line2);border-radius:10px" style-hover="border-color:var(--line)">
          ${c.type === 'image'
            ? html`<img src=${c.thumb} alt="" style="width:64px;height:44px;border-radius:7px;border:1px solid var(--line);background:var(--surf2);object-fit:cover;flex:none" />`
            : html`<div style="width:38px;height:38px;border-radius:9px;background:var(--clipT);display:flex;align-items:center;justify-content:center;flex:none"><i style="color:var(--clip)">${info[0]}</i></div>`}
          <div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:4px">
            <div style="font-size:13px;font-weight:600;line-height:1.4;font-family:${mono ? "'JetBrains Mono',monospace" : 'inherit'};overflow:hidden;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;word-break:break-word;white-space:pre-wrap">${clipTitle(c)}</div>
            <div style="font-size:11px;color:var(--tx3);display:flex;gap:6px;flex-wrap:wrap"><span>${info[1]}</span><span>·</span><span>${c.dev === 'me' ? t('Этот ПК') : v.dev(c.dev).name}</span><span>·</span><span>${ago(c.ts)}</span>${c.meta ? html`<span>·</span><span>${subText(c.meta)}</span>` : null}</div>
          </div>
          <div style="display:flex;gap:2px">
            <${IconBtn} icon="content_copy" title=${t('Скопировать')} onClick=${() => api('clip_copy', c.id)} />
            <${IconBtn} icon="send" title=${t('Отправить')} onClick=${() => api('clip_send', c.id)} />
            <${IconBtn} icon="delete" title=${t('Удалить')} danger=${true} onClick=${() => api('clip_delete', c.id)} />
          </div>
        </div>`;
      })}
      ${!clips.length ? html`<div style="padding:30px;text-align:center;font-size:13px;color:var(--tx3);background:var(--surf);border:1px solid var(--line2);border-radius:10px">${t('История пуста. Скопируйте что-нибудь — оно появится здесь.')}</div>` : null}
    </div>
  </div>`;
}

function Audio({ s, v }) {
  const a = s.settings.audio, au = s.audio;
  const on = au.on;
  const targets = a.targets || [];
  const addable = v.paired.filter(d => !targets.find(x => x.dev === d.id));
  const setTargets = list => api('settings', { audio: { targets: list } });
  const upd = (dev, patch) => {
    setTargets(targets.map(x => x.dev === dev ? { ...x, ...patch } : x));
    api('audio_target_changed', dev);
  };
  const qi = qualityInfo();
  const src = au.sources.find(x => x.id === a.source);
  const srcLabel = !src || src.group === 'system' ? t('весь звук системы') : src.label;
  const groups = [['system', t('Система')], ['input', t('Входные устройства')], ['app', t('Приложения')]];
  return html`<div style="display:flex;flex-direction:column;gap:18px">
    <${PageHead} title=${t('Аудио')} sub=${t('Звук этого компьютера — на колонки и наушники других устройств')}>
      <button onClick=${() => api('audio_toggle')} style="height:36px;padding:0 16px;border-radius:7px;background:${on ? 'var(--surf)' : 'var(--audio)'};color:${on ? 'var(--tx)' : 'var(--onAcc)'};border:1px solid ${on ? 'var(--line)' : 'transparent'};font-weight:700;display:flex;align-items:center;gap:6px" style-hover="filter:brightness(1.08)"><i style="font-size:19px">${on ? 'stop_circle' : 'play_circle'}</i>${on ? t('Остановить трансляцию') : t('Начать трансляцию')}</button>
    <//>
    <${IncomingAudio} s=${s} v=${v} />
    <div style="display:flex;align-items:center;gap:14px;padding:14px 16px;border-radius:10px;background:${on ? 'var(--audioT)' : 'var(--surf)'};border:1px solid var(--line2)">
      <div style="width:38px;height:38px;border-radius:9px;background:var(--audioT);display:flex;align-items:center;justify-content:center"><i style="color:var(--audio)">${on ? 'graphic_eq' : 'volume_off'}</i></div>
      <div style="flex:1;min-width:0"><div style="font-size:14px;font-weight:700">${audioStateText(s, v)}</div><div style="font-size:12px;color:${au.error ? 'var(--bad)' : 'var(--tx2)'}">${au.error || t('Источник: {0}', srcLabel)}</div></div>
      <div style="display:flex;gap:22px">
        <div><div style="font-size:11px;color:var(--tx3);font-weight:600">${t('Задержка')}</div><div style="font-family:'JetBrains Mono',monospace;font-size:15px;margin-top:2px">${on && au.latency ? au.latency + ' ' + t('мс') : '—'}</div></div>
        <div><div style="font-size:11px;color:var(--tx3);font-weight:600">${t('Битрейт')}</div><div style="font-family:'JetBrains Mono',monospace;font-size:15px;margin-top:2px">${qi[a.quality][1]}</div></div>
        <div><div style="font-size:11px;color:var(--tx3);font-weight:600">${t('Формат')}</div><div style="font-family:'JetBrains Mono',monospace;font-size:15px;margin-top:2px">48 ${t('кГц')}</div></div>
      </div>
    </div>
    <div style="display:grid;grid-template-columns:minmax(0,1.35fr) minmax(0,1fr);gap:16px;align-items:start">
      <div style="background:var(--surf);border:1px solid var(--line2);border-radius:10px">
        <div style="padding:14px 16px;border-bottom:1px solid var(--line2);font-size:14px;font-weight:700">${t('Куда выводить звук')}</div>
        ${targets.map(x => {
          const d = v.dev(x.dev), remote = au.remote[x.dev] || {};
          const outs = [''].concat(remote.outputs || []);
          if (x.out && !outs.includes(x.out)) outs.push(x.out);
          return html`<div key=${x.dev} style="padding:14px 16px;border-bottom:1px solid var(--line2);display:flex;flex-direction:column;gap:12px">
            <div style="display:flex;align-items:center;gap:12px">
              <${Toggle} on=${x.on} color="var(--audio)" onClick=${() => upd(x.dev, { on: !x.on })} />
              <i style="color:var(--tx2)">${d.icon}</i>
              <div style="flex:1;min-width:0"><div style="font-size:13px;font-weight:700">${d.name}</div><div style="font-size:11px;color:${remote.error ? 'var(--bad)' : 'var(--tx3)'}">${remote.error || (d.os + (d.online ? (on && x.on && remote.connected ? ' · ' + t('играет') : '') : ' · ' + t('не в сети')))}</div></div>
              <div style="display:flex;align-items:center;gap:6px;height:30px;padding:0 4px 0 8px;border-radius:7px;border:1px solid var(--line);background:var(--surf2)">
                <i style="font-size:16px;color:var(--tx3)">speaker</i>
                <select value=${x.out || ''} onChange=${e => upd(x.dev, { out: e.target.value })} style="border:0;background:none;font-size:12px;font-weight:600;cursor:pointer;max-width:170px">
                  ${outs.map(o => html`<option key=${o} value=${o}>${o || t('По умолчанию')}</option>`)}
                </select>
              </div>
              <${IconBtn} icon="close" title=${t('Убрать')} size=${28} iconSize=${16} danger=${true} onClick=${() => setTargets(targets.filter(y => y.dev !== x.dev))} />
            </div>
            <div style="display:flex;align-items:center;gap:12px;padding-left:48px;opacity:${x.on ? 1 : 0.45}">
              <i style="font-size:18px;color:var(--tx3)">volume_down</i>
              <input type="range" min="0" max="100" value=${x.vol} onChange=${e => upd(x.dev, { vol: +e.target.value })} style="flex:1" />
              <span style="width:36px;text-align:right;font-family:'JetBrains Mono',monospace;font-size:12px;color:var(--tx2)">${x.vol}%</span>
            </div>
          </div>`;
        })}
        ${!targets.length ? html`<div style="padding:18px 16px;font-size:12px;color:var(--tx3)">${t('Добавьте устройство, на котором должен играть звук')}</div>` : null}
        ${addable.length ? html`<div style="padding:12px 16px;display:flex;flex-wrap:wrap;gap:8px">
          ${addable.map(d => html`<button key=${d.id} onClick=${() => setTargets([...targets, { dev: d.id, on: true, vol: 60, out: '' }])} style="flex:1;min-width:150px;height:36px;border-radius:7px;border:1.5px dashed var(--line);color:var(--tx2);font-weight:700;font-size:12px;display:flex;align-items:center;justify-content:center;gap:6px" style-hover="border-color:var(--audio);color:var(--audio)"><i style="font-size:18px">add</i>${d.name}</button>`)}
        </div>` : null}
      </div>
      <div style="display:flex;flex-direction:column;gap:16px">
        <div style="background:var(--surf);border:1px solid var(--line2);border-radius:10px;padding:16px;display:flex;flex-direction:column;gap:12px">
          <div style="display:flex;align-items:center;justify-content:space-between"><div style="font-size:14px;font-weight:700">${t('Источник')}</div><${IconBtn} icon="refresh" title=${t('Обновить список')} size=${26} iconSize=${16} onClick=${() => api('audio_sources_refresh')} /></div>
          <div style="height:36px;border-radius:7px;border:1px solid var(--line);background:var(--surf2);display:flex;align-items:center;gap:8px;padding:0 10px">
            <i style="font-size:18px;color:var(--tx3)">input</i>
            <select value=${a.source} onChange=${e => api('settings', { audio: { source: e.target.value } })} style="flex:1;min-width:0;border:0;background:none;font-size:13px;font-weight:600;cursor:pointer">
              ${groups.map(([g, gl]) => {
                const items = au.sources.filter(x => x.group === g);
                return items.length ? html`<optgroup key=${g} label=${gl}>${items.map(x => html`<option key=${x.id} value=${x.id}>${g === 'system' ? t(x.label) : x.label}</option>`)}</optgroup>` : null;
              })}
              ${!au.sources.find(x => x.id === a.source) ? html`<option value=${a.source}>${t('Недоступно')}</option>` : null}
            </select>
          </div>
          <div style="display:flex;align-items:center;gap:12px">
            <div style="flex:1"><div style="font-size:13px;font-weight:600">${t('Выключить звук на этом ПК')}</div><div style="font-size:11px;color:var(--tx3)">${t('Пока идёт трансляция')}</div></div>
            <${Toggle} on=${a.mute_local} color="var(--audio)" onClick=${() => api('settings', { audio: { mute_local: !a.mute_local } })} />
          </div>
        </div>
        <div style="background:var(--surf);border:1px solid var(--line2);border-radius:10px;padding:16px;display:flex;flex-direction:column;gap:10px">
          <div style="font-size:14px;font-weight:700;margin-bottom:2px">${t('Качество и задержка')}</div>
          ${['low', 'balance', 'hq'].map(id => {
            const sel = a.quality === id;
            return html`<button key=${id} onClick=${() => api('settings', { audio: { quality: id } })} style="display:flex;align-items:center;gap:12px;padding:10px 12px;border-radius:8px;border:1.5px solid ${sel ? 'var(--audio)' : 'var(--line2)'};background:${sel ? 'var(--audioT)' : 'transparent'};text-align:left">
              <span style="width:16px;height:16px;border-radius:50%;border:2px solid ${sel ? 'var(--audio)' : 'var(--line)'};display:flex;align-items:center;justify-content:center;flex:none"><span style="width:8px;height:8px;border-radius:50%;background:${sel ? 'var(--audio)' : 'transparent'}"></span></span>
              <span style="flex:1"><span style="display:block;font-size:13px;font-weight:700">${qi[id][0]}</span><span style="display:block;font-size:11px;color:var(--tx3);margin-top:1px">${qi[id][2]}</span></span>
            </button>`;
          })}
          <div style="display:flex;flex-direction:column;gap:6px;margin-top:6px">
            <div style="display:flex;justify-content:space-between;font-size:12px"><span style="font-weight:600">${t('Буфер')}</span><span style="font-family:'JetBrains Mono',monospace;color:var(--tx2)">${a.buffer} ${t('мс')}</span></div>
            <input type="range" min="10" max="200" value=${a.buffer} onChange=${e => api('settings', { audio: { buffer: +e.target.value } })} />
            <div style="font-size:11px;color:var(--tx3);line-height:1.5">${t('Больше буфер — меньше прерываний при слабом Wi-Fi, но выше задержка')}</div>
          </div>
        </div>
      </div>
    </div>
  </div>`;
}

function History({ s, ui, setUi, v }) {
  const hq = (ui.hq || '').trim().toLowerCase();
  const items = s.history.filter(h => (ui.histFilter === 'all' || h.type === ui.histFilter)
    && (!hq || (h.title + ' ' + v.dev(h.dev).name).toLowerCase().includes(hq))).map(h => histView(h, v));
  const groups = [];
  items.forEach(h => {
    const label = dayLabel(h.ts);
    let g = groups[groups.length - 1];
    if (!g || g.label !== label) groups.push(g = { label, items: [] });
    g.items.push(h);
  });
  return html`<div style="display:flex;flex-direction:column;gap:18px">
    <${PageHead} title=${t('История')} sub=${t('Передачи файлов, синхронизации буфера и трансляции звука')}>
      <button onClick=${() => api('history_clear')} style=${btnGhost} style-hover="background:var(--hover)"><i style="font-size:18px">delete_sweep</i>${t('Очистить историю')}</button>
    <//>
    <div style="display:flex;align-items:center;gap:12px">
      <${Seg} value=${ui.histFilter} onChange=${id => setUi({ histFilter: id })} items=${[{ id: 'all', label: t('Все') }, { id: 'files', label: t('Файлы') }, { id: 'clip', label: t('Буфер') }, { id: 'audio', label: t('Аудио') }]} />
      <div style="flex:1;height:34px;border-radius:8px;background:var(--surf);border:1px solid var(--line);display:flex;align-items:center;gap:8px;padding:0 12px"><i style="font-size:18px;color:var(--tx3)">search</i><input value=${ui.hq} onInput=${e => setUi({ hq: e.target.value })} placeholder=${t('Поиск по названию или устройству')} style="flex:1;min-width:0;border:0;background:none;font-size:13px" /></div>
    </div>
    ${groups.map(g => html`<div key=${g.label} style="display:flex;flex-direction:column;gap:8px">
      <div style="font-size:12px;font-weight:700;color:var(--tx3)">${g.label}</div>
      <div style="background:var(--surf);border:1px solid var(--line2);border-radius:10px;overflow:hidden">
        ${g.items.map(h => html`<${HistRow} key=${h.id} h=${h} />`)}
      </div>
    </div>`)}
    ${!groups.length ? html`<div style="padding:40px;text-align:center;font-size:13px;color:var(--tx3);background:var(--surf);border:1px solid var(--line2);border-radius:10px">${t('Здесь пока ничего нет')}</div>` : null}
  </div>`;
}

function Settings({ s, setUi }) {
  const st = s.settings, me = s.me;
  const set = patch => api('settings', patch);
  const [name, setName] = React.useState(st.device_name);
  const [port, setPort] = React.useState(String(st.port));
  React.useEffect(() => setName(st.device_name), [st.device_name]);
  const row = (label, desc, key) => html`<div key=${key} style="display:flex;align-items:center;gap:12px;padding:12px 0;border-top:1px solid var(--line2)"><div style="flex:1"><div style="font-size:13px;font-weight:600">${label}</div><div style="font-size:11px;color:var(--tx3);margin-top:2px">${desc}</div></div><${Toggle} on=${!!st[key]} onClick=${() => set({ [key]: !st[key] })} /></div>`;
  const card = 'background:var(--surf);border:1px solid var(--line2);border-radius:10px;padding:6px 16px';
  const title = txt => html`<div style="font-size:14px;font-weight:700;padding:12px 0 8px">${txt}</div>`;
  const isMac = me.kind === 'macos';
  return html`<div style="display:flex;flex-direction:column;gap:18px">
    <div>
      <div style="font-size:22px;font-weight:800;letter-spacing:-0.01em">${t('Настройки')}</div>
      <div style="font-size:13px;color:var(--tx2);margin-top:4px">${t('Изменения применяются сразу')}</div>
    </div>
    <div style="display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;align-items:start">
      <div style="display:flex;flex-direction:column;gap:16px">
        <div style=${card}>
          ${title(t('Это устройство'))}
          <div style="padding:10px 0;display:flex;flex-direction:column;gap:6px"><span style="font-size:12px;font-weight:600;color:var(--tx2)">${t('Имя в сети')}</span><input value=${name} onInput=${e => setName(e.target.value)} onBlur=${() => name.trim() && name !== st.device_name && set({ device_name: name.trim() })} onKeyDown=${e => e.key === 'Enter' && e.target.blur()} style="height:36px;border-radius:7px;border:1px solid var(--line);background:var(--surf2);padding:0 10px;font-size:13px;font-weight:600" /></div>
          ${row(t('Запускать вместе с системой'), t('LocalDrop будет готов сразу после входа'), 'autostart')}
          ${row(isMac ? t('Сворачивать в строку меню') : t('Сворачивать в трей'), t('Закрытие окна не выключает программу'), 'tray')}
          ${!isMac ? html`<div style="display:flex;align-items:center;gap:12px;padding:12px 0;border-top:1px solid var(--line2)"><div style="flex:1"><div style="font-size:13px;font-weight:600">${t('«Отправить через LocalDrop» в Проводнике')}</div><div style="font-size:11px;color:var(--tx3);margin-top:2px">${t('Пункт в контекстном меню файлов и папок')}</div></div><${Toggle} on=${!!me.shell_menu} onClick=${() => api('shell_menu', !me.shell_menu)} /></div>` : null}
        </div>
        <div style=${card}>
          ${title(t('Сеть'))}
          <div style="display:flex;align-items:center;gap:12px;padding:10px 0"><div style="flex:1"><div style="font-size:13px;font-weight:600">${t('Сетевой интерфейс')}</div></div>
            <select value=${st.interface} onChange=${e => set({ interface: e.target.value })} style="height:32px;border-radius:7px;border:1px solid var(--line);background:var(--surf2);padding:0 8px;font-size:12px;font-weight:600;max-width:260px">
              <option value="auto">${t('Автоматически')}${me.ip ? ' · ' + me.ip : ''}</option>
              ${(me.ips || []).map(x => html`<option key=${x.ip} value=${x.ip}>${x.name} · ${x.ip}</option>`)}
            </select></div>
          <div style="display:flex;align-items:center;gap:12px;padding:12px 0;border-top:1px solid var(--line2)"><div style="flex:1"><div style="font-size:13px;font-weight:600">${t('Порт')}</div><div style="font-size:11px;color:var(--tx3);margin-top:2px">${port !== String(me.port) ? t('Применится после перезапуска') : t('Должен быть открыт в брандмауэре')}</div></div><input value=${port} onInput=${e => setPort(e.target.value.replace(/\D/g, '').slice(0, 5))} onBlur=${() => { const p = parseInt(port, 10); if (p >= 1024 && p <= 65535) set({ port: p }); else setPort(String(st.port)); }} style="width:90px;height:32px;border-radius:7px;border:1px solid var(--line);background:var(--surf2);padding:0 10px;font-family:'JetBrains Mono',monospace;font-size:12px;text-align:center" /></div>
          ${row(t('Видимость в сети'), t('Другие устройства могут найти этот ПК'), 'visible')}
        </div>
      </div>
      <div style="display:flex;flex-direction:column;gap:16px">
        <div style=${card}>
          ${title(t('Внешний вид'))}
          <div style="padding:10px 0;display:flex;flex-direction:column;gap:8px"><span style="font-size:12px;font-weight:600;color:var(--tx2)">${t('Тема')}</span>
            <${Seg} grow=${true} value=${st.theme} onChange=${id => set({ theme: id })} height=${30} items=${[{ id: 'dark', label: t('Тёмная'), icon: 'dark_mode' }, { id: 'light', label: t('Светлая'), icon: 'light_mode' }, { id: 'system', label: t('Как в системе'), icon: 'contrast' }]} />
          </div>
          <div style="padding:10px 0;display:flex;flex-direction:column;gap:8px;border-top:1px solid var(--line2)"><span style="font-size:12px;font-weight:600;color:var(--tx2)">${t('Язык')}</span>
            <${Seg} grow=${true} value=${st.lang} onChange=${id => set({ lang: id })} height=${30} items=${[{ id: 'ru', label: 'Русский' }, { id: 'en', label: 'English' }]} />
          </div>
          <div style="display:flex;align-items:center;gap:12px;padding:12px 0;border-top:1px solid var(--line2)"><div style="flex:1"><div style="font-size:13px;font-weight:600">${t('Свой цвет для каждой функции')}</div><div style="font-size:11px;color:var(--tx3);margin-top:2px;display:flex;align-items:center;gap:6px"><span style="width:8px;height:8px;border-radius:50%;background:var(--files)"></span>${t('Файлы')}<span style="width:8px;height:8px;border-radius:50%;background:var(--clip);margin-left:4px"></span>${t('Буфер')}<span style="width:8px;height:8px;border-radius:50%;background:var(--audio);margin-left:4px"></span>${t('Аудио')}</div></div><${Toggle} on=${st.feature_colors} onClick=${() => set({ feature_colors: !st.feature_colors })} /></div>
        </div>
        <div style=${card}>
          ${title(t('Файлы'))}
          <div style="padding:10px 0;display:flex;flex-direction:column;gap:6px"><span style="font-size:12px;font-weight:600;color:var(--tx2)">${t('Папка для входящих')}</span>
            <div style="display:flex;gap:8px"><div style="flex:1;min-width:0;height:36px;border-radius:7px;border:1px solid var(--line);background:var(--surf2);padding:0 10px;display:flex;align-items:center;font-family:'JetBrains Mono',monospace;font-size:12px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis">${st.save_dir}</div><button onClick=${() => api('change_save_dir')} style="height:36px;padding:0 12px;border-radius:7px;background:var(--surf2);border:1px solid var(--line);font-size:12px;font-weight:700" style-hover="background:var(--hover)">${t('Изменить')}</button></div>
          </div>
          ${row(t('Автоприём от доверенных'), t('Остальные сопряжённые устройства всегда спрашивают разрешение'), 'auto_accept')}
        </div>
        <div style=${card}>
          ${title(t('Уведомления'))}
          ${row(t('Показывать уведомления'), t('О входящих файлах, буфере и подключениях'), 'notify')}
          ${row(t('Звук уведомлений'), t('Короткий сигнал при получении файла'), 'sounds')}
          <div style="display:flex;gap:8px;padding:12px 0;border-top:1px solid var(--line2)">
            <button onClick=${() => setUi({ onb: 0 })} style="height:30px;padding:0 12px;border-radius:7px;background:var(--surf2);font-size:12px;font-weight:700" style-hover="background:var(--hover)">${t('Пройти приветствие снова')}</button>
            <button onClick=${() => api('open_logs')} style="height:30px;padding:0 12px;border-radius:7px;background:var(--surf2);font-size:12px;font-weight:700" style-hover="background:var(--hover)">${t('Открыть журнал')}</button>
          </div>
        </div>
        <div style="font-size:11px;color:var(--tx3);text-align:center;font-family:'JetBrains Mono',monospace">LocalDrop ${me.version || ''} · ${me.id ? me.id.slice(0, 16) : ''}</div>
      </div>
    </div>
  </div>`;
}

// ---- overlays -------------------------------------------------------------
function Toasts({ toasts, close }) {
  const m = { files: ['var(--files)', 'var(--filesT)', 'folder_open'], clip: ['var(--clip)', 'var(--clipT)', 'content_paste'], audio: ['var(--audio)', 'var(--audioT)', 'graphic_eq'], ok: ['var(--ok)', 'var(--okT)', 'check_circle'], acc: ['var(--acc)', 'var(--accT)', 'devices'], bad: ['var(--bad)', 'var(--badT)', 'error'] };
  return html`<div style="position:absolute;right:16px;bottom:16px;display:flex;flex-direction:column;gap:8px;width:330px;z-index:30">
    ${toasts.map(x => {
      const c = m[x.kind] || ['var(--acc)', 'var(--accT)', 'notifications'];
      return html`<div key=${x.id} style="display:flex;gap:12px;padding:12px;border-radius:10px;background:var(--surf);border:1px solid var(--line);box-shadow:0 12px 30px rgba(0,0,0,.28);animation:ldIn .22s ease-out">
        <div style="width:34px;height:34px;border-radius:8px;background:${c[1]};display:flex;align-items:center;justify-content:center;flex:none"><i style="font-size:19px;color:${c[0]}">${c[2]}</i></div>
        <div style="flex:1;min-width:0"><div style="font-size:13px;font-weight:700">${x.title}</div><div style="font-size:12px;color:var(--tx2);margin-top:2px;line-height:1.4;overflow:hidden;text-overflow:ellipsis">${x.text}</div></div>
        <button onClick=${() => close(x.id)} style="width:24px;height:24px;border-radius:6px;display:flex;align-items:center;justify-content:center;color:var(--tx3)" style-hover="background:var(--hover)"><i style="font-size:16px">close</i></button>
      </div>`;
    })}
  </div>`;
}

function PairModal({ pair }) {
  const [trust, setTrust] = React.useState(true);
  const incoming = pair.dir === 'in';
  return html`<div style="position:absolute;inset:40px 0 0 0;background:rgba(8,9,10,.5);display:flex;align-items:center;justify-content:center;z-index:40">
    <div style="width:400px;background:var(--surf);border:1px solid var(--line);border-radius:14px;padding:24px;display:flex;flex-direction:column;gap:18px;box-shadow:0 30px 60px rgba(0,0,0,.35);animation:ldIn .2s ease-out">
      <div style="display:flex;flex-direction:column;align-items:center;text-align:center;gap:10px">
        <div style="width:56px;height:56px;border-radius:14px;background:var(--accT);display:flex;align-items:center;justify-content:center"><i style="font-size:30px;color:var(--acc)">${pair.icon}</i></div>
        <div style="font-size:16px;font-weight:800">${incoming ? t('{0} хочет подключиться', pair.name) : t('Подключение к {0}', pair.name)}</div>
        <div style="font-size:12px;color:var(--tx2)">${pair.os} · <span style="font-family:'JetBrains Mono',monospace">${pair.ip}</span></div>
      </div>
      <div style="background:var(--surf2);border-radius:10px;padding:16px;text-align:center">
        <div style="font-size:11px;font-weight:700;color:var(--tx3)">${t('Код сопряжения')}</div>
        <div style="font-family:'JetBrains Mono',monospace;font-size:30px;letter-spacing:.14em;margin-top:6px">${pair.code || '··· ···'}</div>
        <div style="font-size:12px;color:var(--tx2);margin-top:8px;line-height:1.5">${incoming ? t('Убедитесь, что такой же код показан на другом устройстве') : t('Подтвердите подключение на другом устройстве — там должен быть такой же код')}</div>
      </div>
      <div style="display:flex;align-items:center;gap:12px">
        <div style="flex:1;font-size:13px;font-weight:600">${t('Сделать доверенным')}<div style="font-size:11px;color:var(--tx3);font-weight:500;margin-top:2px">${t('Принимать файлы и звук без вопросов')}</div></div>
        <${Toggle} on=${trust} onClick=${() => { setTrust(!trust); if (!incoming) api('pair_out_trust', pair.id, !trust); }} />
      </div>
      <div style="display:flex;gap:8px">
        <button onClick=${() => api('pair_respond', pair.id, false, false)} style="flex:1;height:38px;border-radius:8px;background:var(--surf2);font-weight:700" style-hover="background:var(--hover)">${incoming ? t('Отклонить') : t('Отменить')}</button>
        ${incoming
          ? html`<button onClick=${() => api('pair_respond', pair.id, true, trust)} style="flex:1;height:38px;border-radius:8px;background:var(--acc);color:var(--onAcc);font-weight:700" style-hover="filter:brightness(1.08)">${t('Подключить')}</button>`
          : html`<button disabled style="flex:1;height:38px;border-radius:8px;background:var(--acc);color:var(--onAcc);font-weight:700;opacity:.55;display:flex;align-items:center;justify-content:center;gap:8px"><${ScanDot} />${t('Ожидание…')}</button>`}
      </div>
    </div>
  </div>`;
}

function QrScanModal({ qr }) {
  return html`<div style="position:absolute;inset:40px 0 0 0;background:rgba(8,9,10,.55);display:flex;align-items:center;justify-content:center;z-index:45">
    <div style="width:460px;background:var(--surf);border:1px solid var(--line);border-radius:14px;padding:22px;display:flex;flex-direction:column;gap:14px;box-shadow:0 30px 60px rgba(0,0,0,.35);animation:ldIn .2s ease-out">
      <div style="font-size:16px;font-weight:800">${t('Сканирование QR-кода')}</div>
      <div style="height:300px;border-radius:10px;background:#000;overflow:hidden;display:flex;align-items:center;justify-content:center;color:#aaa;font-size:12px">${qr.frame ? html`<img src=${qr.frame} alt="" style="width:100%;height:100%;object-fit:cover" />` : (qr.error || t('Включаю камеру…'))}</div>
      <div style="font-size:12px;color:${qr.error ? 'var(--bad)' : 'var(--tx2)'};line-height:1.5">${qr.error || t('Наведите камеру на QR-код на экране другого устройства (Устройства → Добавить)')}</div>
      <button onClick=${() => api('qr_scan_stop')} style="height:38px;border-radius:8px;background:var(--surf2);font-weight:700" style-hover="background:var(--hover)">${t('Закрыть')}</button>
    </div>
  </div>`;
}

function Onboarding({ s, ui, setUi, v }) {
  const step = ui.onb, st = s.settings;
  const [name, setName] = React.useState(st.device_name);
  const feats = st.features;
  const next = () => {
    if (step === 1 && name.trim() && name !== st.device_name) api('settings', { device_name: name.trim() });
    if (step === 3) { setUi({ onb: -1 }); api('settings', { onboarded: true }); }
    else setUi({ onb: step + 1 });
  };
  const back = () => { if (step === 0) { setUi({ onb: -1 }); api('settings', { onboarded: true }); } else setUi({ onb: step - 1 }); };
  const featsList = [['files', t('Перенос файлов'), t('Файлы и папки по сети'), 'folder_open'], ['clip', t('Буфер обмена'), t('Общий буфер на всех ПК'), 'content_paste'], ['audio', t('Аудио'), t('Звук ПК на другие устройства'), 'graphic_eq']];
  const head = (n, title, sub) => html`<div><div style="font-size:12px;font-weight:700;color:var(--acc)">${t('Шаг {0} из 3', n)}</div><div style="font-size:20px;font-weight:800;margin-top:6px">${title}</div><div style="font-size:13px;color:var(--tx2);margin-top:6px;line-height:1.5">${sub}</div></div>`;
  return html`<div style="position:absolute;inset:40px 0 0 0;background:rgba(8,9,10,.55);display:flex;align-items:center;justify-content:center;z-index:50">
    <div style="width:560px;min-height:440px;background:var(--surf);border:1px solid var(--line);border-radius:16px;padding:32px;display:flex;flex-direction:column;gap:22px;box-shadow:0 30px 70px rgba(0,0,0,.4);animation:ldIn .22s ease-out">
      ${step === 0 ? html`<div style="flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;gap:16px">
        <div style="width:72px;height:72px;border-radius:18px;background:var(--acc);display:flex;align-items:center;justify-content:center"><i style="font-size:40px;color:var(--onAcc)">sync_alt</i></div>
        <div style="font-size:24px;font-weight:800;letter-spacing:-0.01em">${t('Добро пожаловать в LocalDrop')}</div>
        <div style="font-size:14px;color:var(--tx2);line-height:1.55;max-width:400px;text-wrap:pretty">${t('Файлы, буфер обмена и звук между вашими компьютерами — напрямую через домашнюю сеть, без облака и регистрации.')}</div>
        <div style="display:flex;gap:8px;margin-top:6px">
          <span style="display:flex;align-items:center;gap:6px;font-size:12px;font-weight:700;padding:5px 10px;border-radius:999px;background:var(--filesT);color:var(--files)"><i style="font-size:16px">folder_open</i>${t('Файлы')}</span>
          <span style="display:flex;align-items:center;gap:6px;font-size:12px;font-weight:700;padding:5px 10px;border-radius:999px;background:var(--clipT);color:var(--clip)"><i style="font-size:16px">content_paste</i>${t('Буфер')}</span>
          <span style="display:flex;align-items:center;gap:6px;font-size:12px;font-weight:700;padding:5px 10px;border-radius:999px;background:var(--audioT);color:var(--audio)"><i style="font-size:16px">graphic_eq</i>${t('Аудио')}</span>
        </div>
      </div>` : null}
      ${step === 1 ? html`<div style="flex:1;display:flex;flex-direction:column;gap:16px">
        ${head(1, t('Как назвать этот компьютер?'), t('Под этим именем его увидят другие устройства в сети'))}
        <input value=${name} onInput=${e => setName(e.target.value)} onKeyDown=${e => e.key === 'Enter' && next()} style="height:46px;border-radius:9px;border:1.5px solid var(--acc);background:var(--surf2);padding:0 14px;font-size:16px;font-weight:700" />
        <div style="display:flex;align-items:center;gap:12px;padding:14px;border-radius:10px;background:var(--surf2)">
          <i style="font-size:28px;color:var(--tx2)">${s.me.icon || 'computer'}</i>
          <div><div style="font-size:13px;font-weight:700">${name}</div><div style="font-size:11px;color:var(--tx3);font-family:'JetBrains Mono',monospace;margin-top:2px">${s.me.ip || ''} · ${t('так вас увидят другие')}</div></div>
        </div>
      </div>` : null}
      ${step === 2 ? html`<div style="flex:1;display:flex;flex-direction:column;gap:14px">
        ${head(2, t('Что включить?'), t('Всё можно поменять позже в настройках'))}
        ${featsList.map(([k, label, desc, icon]) => html`<div key=${k} style="display:flex;align-items:center;gap:14px;padding:14px;border-radius:10px;border:1.5px solid ${feats[k] ? FC[k][0] : 'var(--line)'};background:var(--surf)">
          <div style="width:40px;height:40px;border-radius:10px;background:${FC[k][1]};display:flex;align-items:center;justify-content:center"><i style="color:${FC[k][0]}">${icon}</i></div>
          <div style="flex:1"><div style="font-size:14px;font-weight:700">${label}</div><div style="font-size:12px;color:var(--tx3);margin-top:2px">${desc}</div></div>
          <${Toggle} on=${feats[k]} color=${FC[k][0]} onClick=${() => api('settings', { features: { [k]: !feats[k] } })} />
        </div>`)}
      </div>` : null}
      ${step === 3 ? html`<div style="flex:1;display:flex;flex-direction:column;gap:14px">
        ${head(3, t('Подключите первое устройство'), t('Установите LocalDrop на другой компьютер и введите этот код'))}
        <div style="display:flex;gap:14px">
          <div style="flex:1;background:var(--surf2);border-radius:10px;padding:16px;display:flex;flex-direction:column;justify-content:center;gap:6px"><div style="font-size:11px;font-weight:700;color:var(--tx3)">${t('Ваш код')}</div><div style="font-family:'JetBrains Mono',monospace;font-size:28px;letter-spacing:.12em">${s.me.code}</div></div>
          <${QR} text=${s.me.qr} size=${110} />
        </div>
        <div style="display:flex;align-items:center;gap:8px;font-size:12px;font-weight:700;color:var(--tx2)"><${ScanDot} />${t('Найдено рядом')}</div>
        ${v.online.filter(d => !d.paired).slice(0, 3).map(d => html`<div key=${d.id} style="display:flex;align-items:center;gap:12px;padding:8px 12px;border-radius:9px;background:var(--surf2)">
          <i style="color:var(--tx2)">${d.icon}</i>
          <div style="flex:1;font-size:13px;font-weight:700">${d.name}</div>
          <button onClick=${() => api('pair_request', d.id)} style="height:28px;padding:0 12px;border-radius:6px;background:var(--acc);color:var(--onAcc);font-size:12px;font-weight:700">${t('Подключить')}</button>
        </div>`)}
        ${!v.online.filter(d => !d.paired).length ? html`<div style="font-size:12px;color:var(--tx3)">${t('Пока никого — устройства появятся здесь автоматически')}</div>` : null}
      </div>` : null}
      <div style="display:flex;align-items:center;gap:10px">
        <div style="display:flex;gap:6px;flex:1">
          ${[0, 1, 2, 3].map(i => html`<span key=${i} style="width:${i === step ? 22 : 6}px;height:6px;border-radius:3px;background:${i <= step ? 'var(--acc)' : 'var(--line)'};transition:all .2s"></span>`)}
        </div>
        <button onClick=${back} style="height:38px;padding:0 14px;border-radius:8px;color:var(--tx2);font-weight:700" style-hover="background:var(--hover)">${step === 0 ? t('Пропустить') : t('Назад')}</button>
        <button onClick=${next} style="height:38px;padding:0 20px;border-radius:8px;background:var(--acc);color:var(--onAcc);font-weight:700;display:flex;align-items:center;gap:6px" style-hover="filter:brightness(1.08)">${step === 0 ? t('Начать') : step === 3 ? t('Готово') : t('Далее')}<i style="font-size:18px">arrow_forward</i></button>
      </div>
    </div>
  </div>`;
}

// ---- app ------------------------------------------------------------------
function useSystemDark() {
  const mq = window.matchMedia('(prefers-color-scheme: dark)');
  const [dark, setDark] = React.useState(mq.matches);
  React.useEffect(() => {
    const f = e => setDark(e.matches);
    mq.addEventListener('change', f);
    return () => mq.removeEventListener('change', f);
  }, []);
  return dark;
}

function App() {
  const s = useStore();
  const sysDark = useSystemDark();
  const [ui, setUiRaw] = React.useState({
    screen: 'home', target: null, drag: false, q: '', devTab: 'nearby', ip: '', port: '53317', code: '',
    clipFilter: 'all', histFilter: 'all', hq: '', onb: -1,
  });
  const setUi = patch => setUiRaw(u => ({ ...u, ...patch }));
  const [toasts, setToasts] = React.useState([]);
  const onbShown = React.useRef(false);
  const uiRef = React.useRef(ui);
  uiRef.current = ui;

  const v = makeView(s, ui);

  React.useEffect(() => {
    if (s.settings && !s.settings.onboarded && !onbShown.current) { onbShown.current = true; setUi({ onb: 0 }); }
    if (s.settings) setUi({ port: String(s.settings.port) });
  }, [!!s.settings]);

  // keep a valid target for file sending
  React.useEffect(() => {
    if (!s.ready) return;
    if (!ui.target || !v.sendable.find(d => d.id === ui.target)) {
      const first = v.sendable[0];
      if ((first && first.id) !== ui.target) setUi({ target: first ? first.id : null });
    }
  }, [s.devices, s.ready]);

  const closeToast = id => setToasts(ts => ts.filter(x => x.id !== id));
  useEvent('toast', d => {
    const id = Date.now() + Math.random();
    const x = { id, kind: d.kind, title: t(d.title, ...(d.targs || [])), text: d.text ? t(d.text, ...(d.args || [])) : '' };
    setToasts(ts => [...ts.slice(-2), x]);
    setTimeout(() => closeToast(id), 4500);
  });
  useEvent('navigate', d => setUi(d));
  useEvent('dropped', paths => {
    const u = uiRef.current;
    setUi({ drag: false });
    if (!paths || !paths.length) return;
    if (!u.target) { events.dispatchEvent(new CustomEvent('toast', { detail: { kind: 'bad', title: t('Некому отправить'), text: t('Сначала подключите устройство') } })); return; }
    if (u.screen !== 'files' && u.screen !== 'home') setUi({ screen: 'files' });
    api('send_paths', u.target, paths);
  });

  const sendTo = kind => {
    if (!ui.target) { events.dispatchEvent(new CustomEvent('toast', { detail: { kind: 'bad', title: t('Некому отправить'), text: t('Сначала подключите устройство') } })); return; }
    api('pick_and_send', ui.target, kind);
  };

  if (!s.ready || !s.settings) {
    return html`<div style="height:100%;display:flex;align-items:center;justify-content:center;background:var(--bg)"><div style="width:48px;height:48px;border-radius:12px;background:var(--acc);display:flex;align-items:center;justify-content:center"><i style="font-size:28px;color:var(--onAcc)">sync_alt</i></div></div>`;
  }
  setLang(s.settings.lang);
  const theme = s.settings.theme;
  const themeEff = theme === 'system' ? (sysDark ? 'dark' : 'light') : theme;
  const go = scr => setUi({ screen: scr });
  const props = { s, ui, setUi, go, v, sendTo };
  const screens = { home: Home, devices: Devices, files: Files, clip: Clipboard, audio: Audio, history: History, settings: Settings };
  const Screen = screens[ui.screen] || Home;

  return html`<div data-theme=${themeEff} data-mono=${s.settings.feature_colors ? '0' : '1'} style="height:100%;background:var(--bg);font-family:'Manrope',system-ui,sans-serif;color:var(--tx);font-size:13px;display:flex;flex-direction:column;position:relative">
    <${TitleBar} me=${s.me} />
    <div style="flex:1;min-height:0;display:flex">
      <${Sidebar} s=${s} ui=${ui} go=${go} v=${v} themeEff=${themeEff} />
      <div style="flex:1;min-width:0;overflow:auto;padding:26px 30px 30px">
        <${Screen} ...${props} />
      </div>
    </div>
    <${Toasts} toasts=${toasts} close=${closeToast} />
    ${s.pair ? html`<${PairModal} key=${s.pair.id + s.pair.dir} pair=${s.pair} />` : null}
    ${s.qr ? html`<${QrScanModal} qr=${s.qr} />` : null}
    ${ui.onb >= 0 ? html`<${Onboarding} ...${props} />` : null}
  </div>`;
}

ReactDOM.createRoot(document.getElementById('root')).render(html`<${App} />`);
onBackendReady(() => api('init').then(st => store.set({ ...st, ready: true })).catch(e => console.error(e)));
