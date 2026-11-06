'use strict';

// moon-vision v2.8.4.9 — per-uid игра/ожидание + честная диагностика кадров.
// Изменения 2.8.4.9 (разбор живого теста навыка v3.1, 06.11):
//   (U1) uid игрока (навык v3.2): POST {game,uid} помнит игру игрока в st.users;
//        POST {q} без game берёт игру uid, затем свежую st.game (стейт Алисы
//        терялся — отсюда «Сначала назови игру» после «Принято, игра»).
//   (U2) GET ?answer=1&uid=… без qid/cid — последний запрос ЭТОГО uid
//        («ну что там» после потери стейта). Глобальный «свежайший» по-прежнему нет.
//   (U3) без кадра — сколько назад пришёл последний кадр и что сделать.
//   (U4) совет/выбор из вариантов, читаемых на кадре, разрешён (fail-closed
//        сохраняется: только текст кадра); show-ответ несёт короткую речь.
// Основа: v2.8.4.5; изменения v2.8.4.7-draft:
//   (1) CID идемпотентен; GET /answer умеет искать запрос по CID после timeout.
//   (2) supersede блокирует публикацию результата старого цикла.
//   (3) без источника фактов модель не вызывается; ответы несут grounding-мета.
//   (4) GET /answer возвращает status=done, как ожидает навык 9.5.3.1.
//   (6) show без marker больше не выдаёт текстовое описание как успех.
//   (5) ОВЕРЛЕЙ СКРЫВАЕТСЯ САМ ЧЕРЕЗ 5 С: configOut отдаёт маркер только
//       в окне MARKER_FRESH_S=5 с; после — marker:null, приложение
//       прячет оверлей по штатному пути «poll без маркера».
//       Ранее (v2.8.4.4) configOut отдавал маркер без окна — оверлей
//       не исчезал до следующего вопроса.
//   Стиль/цвет/геометрия оверлея НЕ МЕНЯЮТСЯ (решение директора:
//   белый оверлей без затемнения устраивает, полос на ТВ нет).
// База: v2.8.4.4 — канал доставки (poll query + marker в POST), гибридный
//   формат маркера, burst {"images":[...]}; v2.8.4.3 — rect/обведи;
//   v2.8.4.2 — JSON-кадр; v2.8.4.1 — TDZ-фикс, локализация.
const crypto = require('crypto');

const TOKEN = process.env.AUTH_TOKEN || '';
const MODEL_URI = process.env.MODEL_URI || '';
const YC_API_KEY = process.env.YC_API_KEY || '';
const BUCKET = process.env.BUCKET || 'moon-assistant';
const S3_ID = process.env.ACCESS_KEY_ID || '';
const S3_SECRET = process.env.SECRET_ACCESS_KEY || '';
const FOLDER_ID = MODEL_URI.split('/')[2] || '';
const AI_URL = 'https://ai.api.cloud.yandex.net/v1/chat/completions';

const MODEL_TIMEOUT_MS = 35000;
const S3_TIMEOUT_MS = 6000;
const FUNC_DEADLINE_MS = 105000;
const FRAME_FRESH_S = 120;
const RING_SIZE = 15;
const FRAMES_TO_MODEL = 6;
const CLAIM_STALE_S = 150;
const Q_TTL_S = 300;
const ANSWER_FRESH_S = 600;
const KEEP_ANSWERS = 8;
const MAX_ANSWER = 400;
const MAX_Q = 200;
const MAX_GAME = 60;
const MAX_BODY = 256 * 1024;
const MARKER_INTERVAL_S = 3;
const MARKER_FRESH_S = 5; // оверлей живёт 5 с после ответа
const BACKEND_VERSION = '2.8.4.9';
const GAME_FRESH_MS = 12 * 3600 * 1000; // игра игрока считается актуальной 12 ч
const KEEP_USERS = 32;

const SYS = 'Ты — Лунный Ассистент. Отвечай по-русски, кратко: 1–3 предложения, до 400 знаков. Верни только JSON вида {\"speech\":\"текст\",\"unk\":false,\"conf\":0.8,\"clarify\":null,\"marker\":null}. Факты разрешено утверждать только если они прямо подтверждаются переданными свежими кадрами. Не используй внутренние знания об игре, догадки, фанатские названия или непроверенную локализацию. Название предмета, локации, персонажа, навыка или стойки произноси только если оно отчётливо читается на русском интерфейсе кадра; иначе не называй его. Если кадры не подтверждают ответ, верни unk=true и conf=0. Если игрок просит совет или выбрать лучший из вариантов, которые видны на кадре (меню, предыстории, диалоговые реплики, предметы), можно рекомендовать один вариант, опираясь только на читаемые на кадре названия и описания, и одной фразой назвать причину из этого текста; это не unk. Если референт нельзя однозначно определить из текущего кадра и вопроса, верни clarify с коротким уточнением. conf — самооценка от 0 до 1, не измеренная вероятность. Не добавляй фактов, которых нет в кадрах.';

const SHOW_PROMPT = ' Это обязательная команда визуального выделения, а не просьба только описать экран. Найди объект, который игрок попросил показать. Если объект однозначно виден и его положение можно локализовать, ОБЯЗАТЕЛЬНО верни marker типа rect с координатами x1,y1,x2,y2 от 0 до 100 (границы всего объекта с небольшим запасом) либо arrow с направлением. В этом случае speech — одна короткая фраза: что именно отмечено (если игрок просил выбрать вариант — какой и почему, по тексту кадра), без описательного абзаца. Если объект не виден, неразличим или нельзя уверенно локализовать, верни unk=true, conf=0, marker:null и speech «Не удалось выделить объект на кадре». Никогда не заменяй выделение длинным словесным описанием.';

function jres(r) {
  return { statusCode: r.code || 200,
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify(r.body) };
}
function hdr(event, name) {
  const h = (event && event.headers) || {};
  return String(h[name] || h[name.toLowerCase()] || h[name.toUpperCase()] || '');
}
function readBody(event) {
  let b = event && event.body !== undefined && event.body !== null ? event.body : '';
  if (event && event.isBase64Encoded) b = Buffer.from(String(b), 'base64');
  return Buffer.isBuffer(b) ? b : Buffer.from(String(b), 'utf8');
}
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
function nowS() { return Math.floor(Date.now() / 1000); }
function normalize(s) {
  return String(s || '').toLowerCase().replace(/ё/g, 'е')
    .replace(/[!.,?;:«»"()\-—–]/g, ' ').replace(/\s+/g, ' ').trim();
}
function sanitizeQ(s) {
  return String(s || '')
    .replace(/[*_#`<>{}]/g, ' ')
    .replace(/[\u{1F000}-\u{1FFFF}\u{2600}-\u{27BF}\u{FE0F}]/gu, ' ')
    .replace(/\s+/g, ' ').trim().slice(0, MAX_Q);
}
function cleanGameStr(s) {
  return String(s || '').replace(/[\u0000-\u001F\u007F]/g, ' ')
    .replace(/\s+/g, ' ').trim().slice(0, MAX_GAME);
}
function isShow(nq) {
  return nq === 'где' || nq.indexOf('покаж') !== -1 ||
    nq.indexOf('обвед') !== -1 ||
    nq.indexOf(' где ') !== -1 || nq.indexOf('где ') === 0;
}
// В backend нет истории реплик: «разве он не X?» нельзя привязать к прошлому референту.
function hasUnresolvedReferent(q) {
  return /(?:^|\s)разве\s+(?:он|она|оно|они)\s+не(?:\s|$)/i.test(normalize(q));
}
function blankState() {
  return { game: '', gameSetAt: 0, lastFrameTs: 0, lastFrameSize: 0,
    qid: 0, frameSeq: 0, ring: [], lastAnswerText: '', lastAnswerAt: 0,
    marker: null, markerSetAt: 0, answers: {}, users: {} };
}
function tokenOk(t) {
  const a = Buffer.from(String(t || '')), b = Buffer.from(TOKEN);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

// ---------- Object Storage (S3, SigV4, path-style) ----------
function enc(s) {
  return encodeURIComponent(s).replace(/[!'()*]/g,
    c => '%' + c.charCodeAt(0).toString(16).toUpperCase());
}
async function s3(method, key, body, contentType, cond) {
  const host = 'storage.yandexcloud.net';
  const payload = body || Buffer.alloc(0);
  const payloadHash = crypto.createHash('sha256').update(payload).digest('hex');
  const amzDate = new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
  const dateStamp = amzDate.slice(0, 8);
  const uri = '/' + enc(BUCKET) + '/' + key.split('/').map(enc).join('/');
  let signedHeaders = 'host;x-amz-content-sha256;x-amz-date';
  let canonicalHeaders = 'host:' + host + '\nx-amz-content-sha256:' + payloadHash +
    '\nx-amz-date:' + amzDate + '\n';
  const headers = { 'x-amz-date': amzDate, 'x-amz-content-sha256': payloadHash };
  if (cond && cond.ifMatch) {
    canonicalHeaders = 'host:' + host + '\nif-match:' + cond.ifMatch.trim() +
      '\nx-amz-content-sha256:' + payloadHash + '\nx-amz-date:' + amzDate + '\n';
    signedHeaders = 'host;if-match;x-amz-content-sha256;x-amz-date';
    headers['If-Match'] = cond.ifMatch.trim();
  } else if (cond && cond.ifNoneMatch) {
    canonicalHeaders = 'host:' + host + '\nif-none-match:' + cond.ifNoneMatch +
      '\nx-amz-content-sha256:' + payloadHash + '\nx-amz-date:' + amzDate + '\n';
    signedHeaders = 'host;if-none-match;x-amz-content-sha256;x-amz-date';
    headers['If-None-Match'] = cond.ifNoneMatch;
  }
  const canonicalRequest = [method, uri, '', canonicalHeaders,
    signedHeaders, payloadHash].join('\n');
  const scope = dateStamp + '/ru-central1/s3/aws4_request';
  const stringToSign = ['AWS4-HMAC-SHA256', amzDate, scope,
    crypto.createHash('sha256').update(canonicalRequest).digest('hex')].join('\n');
  const kDate = crypto.createHmac('sha256', 'AWS4' + S3_SECRET).update(dateStamp).digest();
  const kReg = crypto.createHmac('sha256', kDate).update('ru-central1').digest();
  const kSvc = crypto.createHmac('sha256', kReg).update('s3').digest();
  const kSign = crypto.createHmac('sha256', kSvc).update('aws4_request').digest();
  headers['Authorization'] = 'AWS4-HMAC-SHA256 Credential=' + S3_ID + '/' + scope +
    ', SignedHeaders=' + signedHeaders + ', Signature=' +
    crypto.createHmac('sha256', kSign).update(stringToSign).digest('hex');
  if (method === 'PUT') headers['Content-Type'] = contentType || 'application/octet-stream';
  const res = await fetch('https://' + host + uri, {
    method: method, headers: headers,
    body: method === 'PUT' ? payload : undefined,
    signal: AbortSignal.timeout(S3_TIMEOUT_MS)
  });
  return { status: res.status, etag: res.headers.get('etag') || '',
    body: Buffer.from(await res.arrayBuffer()) };
}
async function getStateEtag() {
  let lastErr = null;
  for (let i = 0; i < 2; i++) {
    try {
      const r = await s3('GET', 'state.json');
      if (r.status === 404) return null;
      if (r.status === 200) {
        try { return { etag: r.etag, obj: JSON.parse(r.body.toString('utf8')) }; }
        catch { throw new Error('invalid state.json'); }
      }
      lastErr = new Error('S3 GET http ' + r.status);
    } catch (e) { lastErr = e; await sleep(300); }
  }
  throw (lastErr || new Error('S3 GET failed'));
}
async function getState() {
  const r = await getStateEtag();
  return (r && r.obj && typeof r.obj === 'object') ? r.obj : blankState();
}
async function s3Put(key, buf, ct) {
  for (let i = 0; i < 2; i++) {
    try { const r = await s3('PUT', key, buf, ct); if (r.status < 300) return true; }
    catch { if (i) return false; await sleep(300); }
  }
  return false;
}
const RANK = { pending: 0, expired: 1, processing: 2, done: 3, superseded: 4, cancelled: 4 };
function recVer(r) {
  if (r.status === 'done') return r.doneAtMs || r.doneAt || 0;
  if (r.status === 'processing') return r.claimedAtMs || r.claimedAt || 0;
  return r.createdAtMs || r.createdAt || 0;
}
function pickRec(a, c, now) {
  // Явная отмена должна побеждать «свежий processing», иначе mergeState оживит старый цикл.
  const ac = a.status === 'superseded' || a.status === 'cancelled';
  const cc = c.status === 'superseded' || c.status === 'cancelled';
  if (ac && c.status === 'processing') return a;
  if (cc && a.status === 'processing') return c;
  const af = a.status === 'processing' && now - (a.claimedAtMs || a.claimedAt || 0) < CLAIM_STALE_S * 1000;
  const cf = c.status === 'processing' && now - (c.claimedAtMs || c.claimedAt || 0) < CLAIM_STALE_S * 1000;
  if (af && !cf) return a;
  if (cf && !af) return c;
  if (RANK[a.status] !== RANK[c.status]) return RANK[a.status] > RANK[c.status] ? a : c;
  if (a.status === 'pending') {
    return (a.createdAtMs || a.createdAt || 0) <= (c.createdAtMs || c.createdAt || 0) ? a : c;
  }
  return recVer(a) >= recVer(c) ? a : c;
}
function mergeState(st, cur, now) {
  if (!cur) return st;
  const m = Object.assign({}, st);
  m.qid = Math.max(st.qid || 0, cur.qid || 0);
  m.frameSeq = Math.max(st.frameSeq || 0, cur.frameSeq || 0);
  const g = (st.gameSetAt || 0) >= (cur.gameSetAt || 0) ? st : cur;
  m.game = g.game || ''; m.gameSetAt = g.gameSetAt || 0;
  const mk = (st.markerSetAt || 0) >= (cur.markerSetAt || 0) ? st : cur;
  m.marker = mk.marker || null; m.markerSetAt = mk.markerSetAt || 0;
  const la = (st.lastAnswerAt || 0) >= (cur.lastAnswerAt || 0) ? st : cur;
  m.lastAnswerText = la.lastAnswerText || ''; m.lastAnswerAt = la.lastAnswerAt || 0;
  const curNewer = (cur.lastFrameTs || 0) > (st.lastFrameTs || 0) ||
    ((cur.lastFrameTs || 0) === (st.lastFrameTs || 0) && (cur.frameSeq || 0) > (st.frameSeq || 0));
  const f = curNewer ? cur : st;
  m.lastFrameTs = f.lastFrameTs || 0; m.lastFrameSize = f.lastFrameSize || 0;
  m.ring = f.ring || [];
  const all = Object.assign({}, cur.answers || {}, st.answers || {});
  const merged = {};
  for (const id of Object.keys(all)) {
    const a = (st.answers || {})[id], c = (cur.answers || {})[id];
    merged[id] = a && c ? pickRec(a, c, now) : (a || c);
  }
  m.answers = merged;
  const us = Object.assign({}, cur.users || {});
  for (const [u, rec] of Object.entries(st.users || {})) {
    const c = us[u];
    if (!c || (rec && (rec.gameSetAt || 0) >= (c.gameSetAt || 0))) us[u] = rec;
  }
  m.users = us;
  return m;
}
function casBackoff(i) { return 30 * (i + 1) + crypto.randomInt(80); }
async function saveState(st) {
  for (let i = 0; i < 4; i++) {
    let cur = null;
    try { cur = await getStateEtag(); }
    catch { await sleep(casBackoff(i)); continue; }
    const merged = mergeState(st, cur ? cur.obj : null, Date.now());
    const buf = Buffer.from(JSON.stringify(merged), 'utf8');
    let r = null;
    try {
      r = await s3('PUT', 'state.json', Buffer.from(JSON.stringify(merged), 'utf8'),
        'application/json', cur ? { ifMatch: cur.etag } : { ifNoneMatch: '*' });
    } catch { r = null; }
    if (r && r.status < 300) { Object.assign(st, merged); return true; }
    await sleep(r && r.status === 412 ? casBackoff(i) : 300);
  }
  return false;
}

// ---------- модель ----------
async function callModel(game, q, frames, frameIds, show) {
  if (!YC_API_KEY || !MODEL_URI) throw new Error('model not configured');
  if (!frames || !frames.length) throw new Error('no grounded frames');
  let userText = 'Игра, названная игроком: ' + (game || 'не указана') +
    '. Вопрос игрока: "' + q + '". Идентификаторы кадров: ' +
    (frameIds || []).join(', ') +
    '. Отвечай только по видимому содержимому этих кадров. Если подтверждения нет — unk=true.';
  if (show) userText += SHOW_PROMPT;
  const content = [ { type: 'text', text: userText } ];
  frames.forEach(f => content.push({ type: 'image_url',
    image_url: { url: 'data:image/jpeg;base64,' + f } }));
  const body = {
    model: MODEL_URI,
    messages: [ { role: 'system', content: SYS }, { role: 'user', content: content } ],
    temperature: 0.1, max_tokens: 300, reasoning_effort: 'none'
  };
  const res = await fetch(AI_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json',
      'Authorization': 'Api-Key ' + YC_API_KEY,
      'OpenAI-Project': FOLDER_ID },
    body: JSON.stringify(body), signal: AbortSignal.timeout(MODEL_TIMEOUT_MS)
  });
  if (res.status !== 200) {
    console.error('mv model: http=' + res.status);
    const err = new Error('model http ' + res.status);
    if (res.status >= 400 && res.status < 500 && res.status !== 408 && res.status !== 429) err.noRetry = true;
    if (res.status === 429) err.rateLimited = true;
    throw err;
  }
  let j = null;
  try { j = JSON.parse(await res.text()); } catch { j = null; }
  if (!j || !j.choices || !j.choices.length) throw new Error('model bad payload');
  return String(j.choices[0].message && j.choices[0].message.content || '');
}
function pctOk(v) {
  return typeof v === 'number' && Number.isFinite(v) && v >= 0 && v <= 100;
}
function validMarker(m) {
  if (!m || typeof m !== 'object') return null;
  if (m.type === 'rect') {
    if (!pctOk(m.x1) || !pctOk(m.y1) || !pctOk(m.x2) || !pctOk(m.y2)) return null;
    return { type: 'rect', x1: Math.round(Math.min(m.x1, m.x2)),
      y1: Math.round(Math.min(m.y1, m.y2)), x2: Math.round(Math.max(m.x1, m.x2)),
      y2: Math.round(Math.max(m.y1, m.y2)) };
  }
  if (m.type === 'arrow' && ['left', 'right', 'up', 'down', 'center'].indexOf(m.dir) !== -1) {
    return { type: 'arrow', dir: m.dir };
  }
  if (m.type === 'marker') {
    if (!pctOk(m.x) || !pctOk(m.y)) return null;
    return { type: 'marker', x: Math.round(m.x), y: Math.round(m.y) };
  }
  return null;
}
function parseAnswer(raw, show) {
  const s = String(raw || '').trim();
  const a = s.indexOf('{'), b = s.lastIndexOf('}');
  const fail = () => ({ text: show
    ? 'Не удалось выделить объект на кадре. Попробуй приблизить его и повтори «покажи».'
    : 'Не могу подтвердить ответ по кадру.',
    marker: null, unk: true, conf: 0, clarify: null });
  if (a === -1 || b <= a) return fail();
  try {
    const j = JSON.parse(s.slice(a, b + 1));
    if (!j || typeof j !== 'object' || typeof j.speech !== 'string' ||
        typeof j.unk !== 'boolean' || typeof j.conf !== 'number' ||
        !Number.isFinite(j.conf) || j.conf < 0 || j.conf > 1) return fail();
    const clarity = j.clarify && typeof j.clarify === 'object' &&
      typeof j.clarify.text === 'string' ?
      { kind: String(j.clarify.kind || 'referent').slice(0, 32),
        text: String(j.clarify.text).slice(0, 160) } : null;
    if (clarity) return { text: clarity.text, marker: null,
      unk: true, conf: 0, clarify: clarity };
    const marker = show && !j.unk ? validMarker(j.marker) : null;
    if (show) {
      // Не выдаём словесное описание за выполненное визуальное выделение.
      if (j.unk || !marker) return fail();
      const sp = String(j.speech || '').trim();
      return { text: sp && sp.length <= 200 ? sp : 'Отметил объект на экране.', marker: marker,
        unk: false, conf: j.conf, clarify: null };
    }
    return { text: j.unk ? String(j.speech || 'Не могу подтвердить ответ по кадру.') : j.speech,
      marker: null, unk: j.unk, conf: j.unk ? 0 : j.conf, clarify: null };
  } catch {
    return fail();
  }
}

// ---------- проводной формат маркера ----------
function emitMarker(m) {
  if (!m) return null;
  if (m.type === 'rect') {
    return { type: 'marker',
      x: Math.round((m.x1 + m.x2) / 2),
      y: Math.round((m.y1 + m.y2) / 2),
      x1: m.x1, y1: m.y1, x2: m.x2, y2: m.y2 };
  }
  return m;
}
function freshMarker(st) {
  const m = st && st.marker;
  if (!m) return null;
  return (Date.now() - (st.markerSetAt || 0) <= MARKER_FRESH_S * 1000) ? m : null;
}
function freshAnswerWithMarker(st) {
  const entries = Object.keys(st.answers || {}).map(id => ({
    id: id, rec: st.answers[id]
  })).filter(x => x.rec);
  if (!entries.length) return null;
  // Маркер старого show-запроса не должен всплывать после нового вопроса.
  entries.sort((a, b) =>
    ((b.rec.createdAtMs || (b.rec.createdAt || 0) * 1000) -
     (a.rec.createdAtMs || (a.rec.createdAt || 0) * 1000)));
  const latest = entries[0];
  const r = latest.rec;
  if (!r || r.status !== 'done' || !(r.show === true || isShow(r.q)) || !r.marker) return null;
  const doneAt = r.doneAtMs || (r.doneAt || 0) * 1000;
  if (Date.now() - doneAt > MARKER_FRESH_S * 1000) return null;
  return { id: latest.id, rec: r };
}

// ---------- пайплайн вопроса ----------
async function latestBurst(st) {
  const now = nowS();
  const fresh = (st.ring || [])
    .filter(e => e && e.key && now - e.ts >= -5 && now - e.ts <= FRAME_FRESH_S)
    .sort((a, b) => a.ts - b.ts).slice(-FRAMES_TO_MODEL);
  if (!fresh.length) return null;
  const rs = await Promise.all(fresh.map(e => s3('GET', e.key).catch(() => null)));
  const frames = [], frameIds = [];
  for (let i = 0; i < rs.length; i++) {
    const r = rs[i];
    if (r && r.status === 200 && r.body.length) {
      frames.push(r.body.toString('base64'));
      const m = String(fresh[i].key).match(/f(\d+)\.jpg$/);
      frameIds.push(m ? 'f' + m[1] : String(fresh[i].key).slice(0, 80));
    }
  }
  return frames.length ? { frames: frames, frameIds: frameIds,
    frameId: frameIds.length ? frameIds[frameIds.length - 1] : null } : null;
}
async function saveClaimResult(id, claimId, out) {
  for (let i = 0; i < 4; i++) {
    let cur = null;
    try { cur = await getStateEtag(); }
    catch { await sleep(casBackoff(i)); continue; }
    if (!cur) return false;
    const rec = (cur.obj.answers || {})[id];
    if (!rec || rec.claimId !== claimId || rec.status === 'superseded' ||
        rec.status === 'cancelled') return false;
    rec.status = 'done';
    rec.doneAt = nowS();
    rec.doneAtMs = Date.now();
    rec.text = out.text;
    rec.marker = out.marker;
    rec.unk = !!out.unk;
    rec.src = out.src || 'none';
    rec.frame_id = out.frame_id || null;
    rec.conf = typeof out.conf === 'number' ? out.conf : null;
    rec.resultStatus = out.resultStatus === 'clarify' ? 'clarify' : 'done';
    cur.obj.lastAnswerText = out.text;
    cur.obj.lastAnswerAt = Date.now();
    if (out.marker) { cur.obj.marker = out.marker; cur.obj.markerSetAt = Date.now(); }
    let r = null;
    try {
      r = await s3('PUT', 'state.json', Buffer.from(JSON.stringify(cur.obj), 'utf8'),
        'application/json', { ifMatch: cur.etag });
    } catch { r = null; }
    if (r && r.status < 300) return true;
    await sleep(r && r.status === 412 ? casBackoff(i) : 300);
  }
  return false;
}
async function processOne(st, id) {
  const rec0 = st.answers[id];
  if (!rec0 || ['done', 'expired', 'superseded', 'cancelled'].includes(rec0.status)) return;
  if (rec0.status === 'processing' && nowS() - (rec0.claimedAt || 0) < CLAIM_STALE_S) return;
  const claimId = crypto.randomUUID();
  rec0.status = 'processing';
  rec0.claimedAt = nowS();
  rec0.claimedAtMs = Date.now();
  rec0.claimId = claimId;
  if (!(await saveState(st))) return;
  const fresh = await getState();
  const mine = (fresh.answers || {})[id];
  if (!mine || mine.claimId !== claimId || mine.status !== 'processing') return;
  st.answers = fresh.answers; st.qid = fresh.qid;
  const show = isShow(mine.q);
  let frameInfo = null, out = null;
  try { frameInfo = await latestBurst(st); } catch { frameInfo = null; }

  // Без кадра и без названной игры — только уточнение, никаких фактов из памяти модели.
  const fix = ' Держи стрим Moonlight на экране телефона или в режиме «картинка в картинке» и спроси снова.';
  if (!frameInfo && !mine.game) {
    out = { text: 'Не вижу экран игры и не знаю, во что ты играешь. Скажи «играю в …». ' + frameAgeText(st) + fix,
      marker: null, unk: true, conf: 0, src: 'none', frame_id: null,
      resultStatus: 'clarify' };
  } else if (!frameInfo) {
    // В этой версии нет подключённой проверенной KB/RAG. Без кадра фактический ответ запрещён.
    out = { text: 'Не вижу экран игры. ' + frameAgeText(st) + fix,
      marker: null, unk: true, conf: 0, src: 'none', frame_id: null,
      resultStatus: 'done' };
  } else if (hasUnresolvedReferent(mine.q)) {
    // P4: нет истории диалога/ctx — не угадываем референт по формулировке.
    out = { text: 'Кого ты имеешь в виду? Уточни персонажа или объект.',
      marker: null, unk: true, conf: 0, src: 'none', frame_id: null,
      resultStatus: 'clarify' };
  } else {
    try {
      const parsed = parseAnswer(await callModel(mine.game, mine.q,
        frameInfo.frames, frameInfo.frameIds, show), show);
      out = Object.assign({}, parsed, {
        src: parsed.unk ? 'none' : 'screen',
        frame_id: parsed.unk ? null : frameInfo.frameId,
        resultStatus: parsed.clarify ? 'clarify' : 'done'
      });
    } catch (e) {
      console.error('mv process q' + id + ': ' + (e && e.message));
      out = { text: 'Не удалось проверить ответ по кадру. Попробуй ещё раз.',
        marker: null, unk: true, conf: 0, src: 'none', frame_id: null,
        resultStatus: 'done' };
    }
  }
  const saved = await saveClaimResult(id, claimId, out);
  console.log('mv answer q' + id + ' saved=' + saved + ' len=' + out.text.length +
    ' show=' + !!mine.show + ' marker=' + (out.marker ? out.marker.type : 'none') +
    ' src=' + out.src + ' frame=' + (out.frame_id || 'none') + ' unk=' + out.unk);
}

// ---------- обработчики ----------
async function handleGame(game, supersedeValue, uidValue) {
  let st;
  try { st = await getState(); } catch { return { code: 503, body: { status: 'wait' } }; }
  const supersede = cleanCid(supersedeValue);
  let old = findAnswerByCid(st, supersede);
  if (!old && /^\d+$/.test(String(supersedeValue || ''))) {
    const rec = st.answers[String(supersedeValue)];
    if (rec) old = { id: String(supersedeValue), rec: rec };
  }
  if (old && !['done', 'expired', 'superseded', 'cancelled'].includes(old.rec.status)) {
    old.rec.status = 'superseded';
    old.rec.supersededAt = nowS();
  }
  st.game = cleanGameStr(game);
  st.gameSetAt = Date.now();
  rememberUserGame(st, cleanUid(uidValue), st.game);
  if (!(await saveState(st))) return { code: 503, body: { status: 'wait' } };
  console.log('mv game set: ' + st.game);
  return { code: 200, body: { status: 'ok' } };
}
async function commitFrame(buf, size) {
  for (let i = 0; i < 4; i++) {
    let cur = null;
    try { cur = await getStateEtag(); }
    catch { await sleep(casBackoff(i)); continue; }
    const base = cur ? cur.obj : blankState();
    const seq = (base.frameSeq || 0) + 1;
    const key = 'frames/f' + seq + '.jpg';
    const ts = nowS();
    const ring = (base.ring || []).filter(e => e && e.key !== key)
      .concat([{ ts: ts, size: size, key: key }]).slice(-RING_SIZE);
    const upd = Object.assign({}, base,
      { frameSeq: seq, ring: ring, lastFrameTs: ts, lastFrameSize: size });
    const fileOk = await s3Put(key, buf, 'image/jpeg');
    let r = null;
    try {
      r = await s3('PUT', 'state.json', Buffer.from(JSON.stringify(upd), 'utf8'),
        'application/json', cur ? { ifMatch: cur.etag } : { ifNoneMatch: '*' });
    } catch { r = null; }
    if (r && r.status < 300) {
      const lastOk = await s3Put('frames/last.jpg', buf, 'image/jpeg');
      return { ok: true, saved: fileOk && lastOk, seq: seq, state: upd };
    }
    await sleep(r && r.status === 412 ? casBackoff(i) : 300);
  }
  return { ok: false, saved: false, seq: 0, state: null };
}
async function handleFrame(buf) {
  if (!buf.length) return { code: 400, body: { status: 'bad_request' } };
  const res = await commitFrame(buf, buf.length);
  if (!res.ok || !res.saved) return { code: 503, body: { status: 'wait' } };
  console.log('mv frame seq=' + res.seq + ' size=' + buf.length);
  return { code: 200, body: { status: 'ok',
    marker: emitMarker(freshMarker(res.state)) } };
}
function cleanUid(value) {
  const u = String(value || '').trim();
  return /^u-[a-f0-9]{8,48}$/.test(u) ? u : '';
}
function rememberUserGame(st, uid, game) {
  if (!uid) return;
  st.users = st.users || {};
  st.users[uid] = { game: game, gameSetAt: Date.now() };
  const ids = Object.keys(st.users);
  if (ids.length > KEEP_USERS) {
    ids.sort((a, b) => (st.users[a].gameSetAt || 0) - (st.users[b].gameSetAt || 0));
    for (const id of ids.slice(0, ids.length - KEEP_USERS)) delete st.users[id];
  }
}
// U1: игра из запроса > игра этого uid > свежая глобальная (однопользовательский стенд).
function effectiveGame(st, requested, uid) {
  if (requested) return requested;
  const now = Date.now();
  const u = uid && st.users && st.users[uid];
  if (u && u.game && now - (u.gameSetAt || 0) <= GAME_FRESH_MS) return cleanGameStr(u.game);
  if (st.game && now - (st.gameSetAt || 0) <= GAME_FRESH_MS) return cleanGameStr(st.game);
  return '';
}
// U2: последний запрос конкретного uid (не глобальный «свежайший»).
function findLatestByUid(st, uid) {
  if (!uid) return null;
  let best = null;
  for (const id of Object.keys(st.answers || {})) {
    const rec = st.answers[id];
    if (!rec || rec.uid !== uid) continue;
    const t = rec.createdAtMs || (rec.createdAt || 0) * 1000;
    if (!best || t > best.t) best = { id: id, rec: rec, t: t };
  }
  return best;
}
function frameAgeText(st) {
  if (!st || !st.lastFrameTs) return 'Кадры с телефона ещё не приходили.';
  const min = Math.round(Math.max(0, nowS() - st.lastFrameTs) / 60);
  if (min < 1) return 'Последний кадр пришёл меньше минуты назад, но он уже устарел.';
  if (min < 120) return 'Последний кадр пришёл ' + min + ' мин назад.';
  return 'Последний кадр пришёл больше ' + Math.floor(min / 60) + ' ч назад.';
}
function cleanCid(value) {
  const cid = String(value || '').trim();
  return /^[A-Za-z0-9._-]{1,64}$/.test(cid) ? cid : '';
}
function findAnswerByCid(st, cid) {
  if (!cid) return null;
  for (const id of Object.keys(st.answers || {})) {
    const rec = st.answers[id];
    if (rec && rec.cid === cid) return { id: id, rec: rec };
  }
  return null;
}
function answerPayload(id, rec) {
  // Старые записи без grounding-метаданных не выдаём как подтверждённый факт.
  const legacy = !Object.prototype.hasOwnProperty.call(rec, 'src');
  return { status: legacy ? 'done' : (rec.resultStatus || 'done'),
    qid: Number(id), cid: rec.cid || '',
    text: legacy ? 'Этот ответ создан до проверки источников. Задай вопрос ещё раз.' : (rec.text || ''),
    marker: legacy ? null : emitMarker(rec.marker || null),
    unk: legacy ? true : !!rec.unk,
    src: legacy ? 'none' : (rec.src || 'none'),
    frame_id: legacy ? null : (rec.frame_id || null),
    conf: legacy ? 0 : (typeof rec.conf === 'number' ? rec.conf : null) };
}
async function handleAsk(q, game, cidValue, supersedeValue, uidValue) {
  let st;
  try { st = await getState(); } catch { return { code: 503, body: { status: 'wait' } }; }
  const nq = normalize(q);
  if (nq.length < 2) return { code: 200, body: { status: 'bad_request' } };
  const now = nowS();
  const uid = cleanUid(uidValue);
  // U1: игра из запроса; если навык её потерял — игра этого uid / свежая глобальная.
  const requestedGame = effectiveGame(st, game ? cleanGameStr(game) : '', uid);
  const cid = cleanCid(cidValue);
  if (cidValue && !cid) return { code: 400, body: { status: 'bad_request', why: 'invalid_cid' } };
  if (game) { st.game = requestedGame; st.gameSetAt = Date.now(); rememberUserGame(st, uid, requestedGame); }

  // Повтор POST с тем же CID возвращает уже существующий запрос, не создавая новый.
  if (cid) {
    const existing = findAnswerByCid(st, cid);
    if (existing) {
      if (existing.rec.q !== nq || existing.rec.game !== requestedGame) {
        return { code: 409, body: { status: 'cid_conflict' } };
      }
      if (existing.rec.status === 'done') return { code: 200, body: answerPayload(existing.id, existing.rec) };
      if (existing.rec.status === 'superseded' || existing.rec.status === 'cancelled' || existing.rec.status === 'expired') {
        return { code: 200, body: { status: existing.rec.status, qid: Number(existing.id), cid: cid } };
      }
      return { code: 200, body: { status: 'query', qid: Number(existing.id), cid: cid } };
    }
  }

  // Supersede принимается по предыдущему CID или внутреннему qid.
  const supersede = String(supersedeValue || '').trim();
  if (supersede) {
    let old = findAnswerByCid(st, cleanCid(supersede));
    if (!old && /^\d+$/.test(supersede)) {
      const rec = st.answers[supersede];
      if (rec) old = { id: supersede, rec: rec };
    }
    if (old && !['done', 'expired', 'superseded', 'cancelled'].includes(old.rec.status)) {
      old.rec.status = 'superseded';
      old.rec.supersededAt = now;
      await saveState(st);
    }
  }

  const showQ = isShow(nq);
  // Совместимость со старым клиентом без CID; клиенты v2 используют только собственный CID.
  if (!cid) {
    for (const id of Object.keys(st.answers)) {
      const rec = st.answers[id];
      if (!rec || rec.cid || rec.q !== nq || rec.game !== requestedGame) continue;
      if (rec.status !== 'done' && rec.status !== 'expired' &&
          rec.status !== 'superseded' && now - (rec.createdAt || 0) < 180) {
        return { code: 200, body: { status: 'query', qid: Number(id) } };
      }
      if (rec.status === 'done' && !showQ && now - (rec.doneAt || 0) <= ANSWER_FRESH_S) {
        return { code: 200, body: answerPayload(id, rec) };
      }
    }
  }

  const createdAtMs = Date.now();
  const qid = cid ? (parseInt(crypto.createHash('sha256').update(cid).digest('hex').slice(0, 12), 16) || 1) :
    (Math.floor(createdAtMs / 1000) * (2 ** 20) + crypto.randomInt(2 ** 20));
  st.qid = Math.max(st.qid || 0, qid);
  st.answers[String(qid)] = { status: 'pending', q: nq, game: requestedGame,
    cid: cid || '', uid: uid, show: showQ, createdAt: now, createdAtMs: createdAtMs };
  st.marker = null; st.markerSetAt = Date.now();
  const ids = Object.keys(st.answers);
  if (ids.length > KEEP_ANSWERS) {
    const terminal = ids.filter(id => {
      const r = st.answers[id];
      return r && ['done', 'expired', 'superseded', 'cancelled'].includes(r.status);
    }).sort((a, b) => Number(a) - Number(b));
    let overflow = ids.length - KEEP_ANSWERS;
    for (const id of terminal) { if (overflow-- <= 0) break; delete st.answers[id]; }
  }
  if (!(await saveState(st))) return { code: 503, body: { status: 'wait' } };
  const after = await getState();
  await processOne(after, String(qid));
  const fin = await getState();
  const frec = (fin.answers || {})[String(qid)];
  if (frec && frec.status === 'done' && nowS() - (frec.doneAt || 0) <= ANSWER_FRESH_S) {
    return { code: 200, body: answerPayload(qid, frec) };
  }
  if (frec && (frec.status === 'superseded' || frec.status === 'cancelled')) {
    return { code: 200, body: { status: frec.status, qid: qid, cid: cid } };
  }
  return { code: 200, body: { status: 'query', qid: qid, cid: cid } };
}
async function handleAnswer(qidParam, cidValue, uidValue) {
  let st;
  try { st = await getState(); }
  catch { return { code: 503, body: { status: 'server_error' } }; }
  const now = nowS();
  const cid = cleanCid(cidValue);
  if (cidValue && !cid) return { code: 400, body: { status: 'bad_request' } };
  let id = qidParam ? String(qidParam) : '';
  if (!id && cid) {
    const found = findAnswerByCid(st, cid);
    if (found) id = found.id;
  }
  if (!id && !cidValue) {
    const latest = findLatestByUid(st, cleanUid(uidValue));
    if (latest) id = latest.id;
  }
  // Без qid/CID/uid не выбираем глобально «самый свежий» ответ: это мог быть чужой запрос.
  if (!id) return { code: 200, body: { status: 'none', cid: cid || '' } };
  let rec = (st.answers || {})[id];
  if (!rec) return { code: 409, body: { status: 'expired', qid: Number(id), cid: cid || '' } };
  if (cid && rec.cid && rec.cid !== cid) return { code: 404, body: { status: 'not_found' } };
  if (rec.status === 'superseded' || rec.status === 'cancelled' || rec.status === 'expired') {
    return { code: 200, body: { status: rec.status, qid: Number(id), cid: rec.cid || '' } };
  }
  if (rec.status === 'processing' && now - (rec.claimedAt || 0) < CLAIM_STALE_S) {
    return { code: 200, body: { status: 'query', qid: Number(id), cid: rec.cid || '' } };
  }
  if (rec.status === 'done') return (now - (rec.doneAt || 0) > ANSWER_FRESH_S)
    ? { code: 409, body: { status: 'expired' } } : { code: 200, body: answerPayload(id, rec) };
  if (now - (rec.createdAt || 0) > Q_TTL_S) {
    rec.status = 'expired';
    await saveState(st);
    return { code: 409, body: { status: 'expired' } };
  }
  await processOne(st, id);
  const latest = await getState();
  rec = (latest.answers || {})[id];
  if (rec && rec.status === 'done' && nowS() - (rec.doneAt || 0) <= ANSWER_FRESH_S) {
    return { code: 200, body: answerPayload(id, rec) };
  }
  if (rec && (rec.status === 'superseded' || rec.status === 'cancelled')) {
    return { code: 200, body: { status: rec.status, qid: Number(id), cid: rec.cid || '' } };
  }
  return { code: 200, body: { status: 'query', qid: Number(id), cid: rec ? (rec.cid || '') : '' } };
}

function stateOut(st) {
  // stateOut — маркер БЕЗ окна (для замеров AH); канал приложения — poll/config
  return { code: 200, body: { ts: st.lastFrameTs || 0, text: st.lastAnswerText || '',
    frameSize: st.lastFrameSize || 0, game: st.game || '',
    marker: emitMarker(st.marker || null), version: BACKEND_VERSION } };
}
function configOut(st) {
  // Маркер только в окне 5 с — иначе оверлей не исчезает сам
  return { code: 200, body: { status: 'ok', version: BACKEND_VERSION, capture: true,
    interval: MARKER_INTERVAL_S, intervalMs: MARKER_INTERVAL_S * 1000,
    game: st.game || '', marker: emitMarker(freshMarker(st)),
    ts: st.lastFrameTs || 0 } };
}
function pollOut(st) {
  const fa = freshAnswerWithMarker(st);
  if (fa) {
    return { code: 200, body: { status: 'query', qid: Number(fa.id),
      q: fa.rec.q || '', marker: emitMarker(fa.rec.marker),
      frame_id: fa.rec.frame_id || null, src: fa.rec.src || 'none',
      conf: typeof fa.rec.conf === 'number' ? fa.rec.conf : null } };
  }
  return configOut(st);
}

// ---------- маршрутизация ----------
async function route(event) {
  if (!TOKEN) return { code: 500, body: { status: 'server_error', why: 'AUTH_TOKEN not set' } };
  const qp = (event && event.queryStringParameters) || {};
  if (!tokenOk(qp.token)) return { code: 403, body: { status: 'forbidden' } };
  const method = String((event && event.httpMethod) || 'GET').toUpperCase();
  if (method === 'GET' && qp.ping === '1') {
    return { code: 200, body: { status: 'pong', version: BACKEND_VERSION } };
  }
  if (method === 'GET' && (qp.health === '1' || qp.stats === '1')) {
    const st = await getState();
    if (qp.health === '1') {
      const age = st.lastFrameTs ? Math.max(0, nowS() - st.lastFrameTs) : null;
      return { code: 200, body: { ok: true, ver: BACKEND_VERSION, frame_age_s: age, poll_age_s: null } };
    }
    const since = nowS() - 86400;
    const rows = Object.values(st.answers || {}).filter(r => r && (r.createdAt || 0) >= since);
    return { code: 200, body: { ver: BACKEND_VERSION, scope: 'retained_answers_only',
      retained: rows.length, retention_limit: KEEP_ANSWERS, asked: rows.length,
      answered: rows.filter(r => r.status === 'done' && !r.unk).length,
      unk: rows.filter(r => r.status === 'done' && r.unk).length,
      superseded: rows.filter(r => r.status === 'superseded').length,
      pending: rows.filter(r => r.status === 'pending' || r.status === 'processing').length } };
  }
  console.log('mv: ' + method + ' ' + Object.keys(qp).join(','));
  if (method === 'GET') {
    if (qp.state === '1') return stateOut(await getState());
    if (qp.ask !== undefined && qp.ask.trim()) {
      return handleAsk(sanitizeQ(qp.ask),
        typeof qp.game === 'string' ? qp.game : '', qp.cid, qp.supersede, qp.uid);
    }
    if (qp.answer === '1') {
      if (qp.qid !== undefined) {
        if (!/^\d+$/.test(String(qp.qid))) {
          return { code: 400, body: { status: 'bad_request' } };
        }
        const n = Number(qp.qid);
        if (!Number.isSafeInteger(n) || n <= 0) {
          return { code: 400, body: { status: 'bad_request' } };
        }
        return handleAnswer(n, qp.cid, qp.uid);
      }
      return handleAnswer(0, qp.cid, qp.uid);
    }
    if (qp.poll === '1') return pollOut(await getState());
    return configOut(await getState());
  }
  if (method === 'POST') {
    const body = readBody(event);
    if (body.length > MAX_BODY) return { code: 413, body: { status: 'bad_request' } };
    const isJpeg = body.length > 2 && body[0] === 0xFF && body[1] === 0xD8 && body[2] === 0xFF;
    if (hdr(event, 'content-type').toLowerCase().indexOf('image/') === 0) {
      if (!isJpeg) return { code: 400, body: { status: 'bad_request' } };
      return handleFrame(body);
    }
    if (isJpeg) return handleFrame(body);
    let j = null;
    try { j = JSON.parse(body.toString('utf8')); } catch { j = null; }
    if (!j || typeof j !== 'object') return { code: 400, body: { status: 'bad_request' } };
    if (typeof j.image === 'string' && j.image.length > 16) {
      const buf = Buffer.from(j.image, 'base64');
      if (buf.length < 3 ||
          !(buf[0] === 0xFF && buf[1] === 0xD8 && buf[2] === 0xFF)) {
        return { code: 400, body: { status: 'bad_request' } };
      }
      return handleFrame(buf);
    }
    if (Array.isArray(j.images) && j.images.length) {
      let saved = 0;
      for (const it of j.images.slice(0, RING_SIZE)) {
        if (typeof it !== 'string' || it.length < 16) continue;
        const b = Buffer.from(it, 'base64');
        if (b.length >= 3 && b[0] === 0xFF && b[1] === 0xD8 && b[2] === 0xFF) {
          const r2 = await commitFrame(b, b.length);
          if (r2.ok && r2.saved) saved++;
        }
      }
      if (saved > 0) {
        const cur = await getState();
        return { code: 200, body: { status: 'ok', saved: saved,
          marker: emitMarker(freshMarker(cur)) } };
      }
      return { code: 400, body: { status: 'bad_request' } };
    }
    if (typeof j.q === 'string' && j.q.trim()) {
      return handleAsk(sanitizeQ(j.q), typeof j.game === 'string' ? j.game : '', j.cid, j.supersede, j.uid);
    }
    if (typeof j.game === 'string' && j.game.trim()) return handleGame(j.game, j.supersede, j.uid);
    return { code: 400, body: { status: 'bad_request' } };
  }
  return { code: 405, body: { status: 'method_not_allowed' } };
}

module.exports.handler = async function (event) {
  try { return jres(await route(event)); }
  catch (e) {
    console.error('mv error: ' + (e && e.message));
    return jres({ code: 500, body: { status: 'server_error' } });
  }
};
