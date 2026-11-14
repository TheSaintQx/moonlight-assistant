'use strict';
// Сквозной тест навыка и бэкенда в одном процессе: fetch подменён на
// in-memory S3 (с ETag/If-Match), фейковую модель и прямой вызов moon-vision.
// Запуск: node --test cloud/test
const test = require('node:test');
const assert = require('node:assert');
const crypto = require('crypto');

process.env.AUTH_TOKEN = 'test-token';
process.env.MOON_TOKEN = 'test-token';
process.env.MODEL_URI = 'gpt://folder/model/latest';
process.env.YC_API_KEY = 'k';
process.env.ACCESS_KEY_ID = 'a';
process.env.SECRET_ACCESS_KEY = 's';

const store = new Map();
const model = { delayMs: 0, reply: { speech: 'На экране меню выбора предыстории.', unk: false, conf: 0.8, clarify: null, marker: null } };

function abortable(signal, work) {
  return new Promise((resolve, reject) => {
    if (signal && signal.aborted) return reject(signal.reason);
    const onAbort = () => reject(signal.reason);
    if (signal) signal.addEventListener('abort', onAbort, { once: true });
    work().then(resolve, reject).finally(() => signal && signal.removeEventListener('abort', onAbort));
  });
}
function mkRes(status, body, headers) {
  const buf = Buffer.isBuffer(body) ? body : Buffer.from(String(body || ''), 'utf8');
  return { status, headers: { get: k => (headers || {})[k.toLowerCase()] || null },
    text: async () => buf.toString('utf8'), arrayBuffer: async () => buf };
}
let vision;
global.fetch = async (url, init) => {
  init = init || {};
  const u = new URL(url);
  if (u.host === 'storage.yandexcloud.net') {
    const key = decodeURIComponent(u.pathname.split('/').slice(2).join('/'));
    const h = init.headers || {};
    const cur = store.get(key);
    if (init.method === 'GET') return cur ? mkRes(200, cur.body, { etag: cur.etag }) : mkRes(404, '');
    if (h['If-Match'] && (!cur || cur.etag !== h['If-Match'])) return mkRes(412, '');
    if (h['If-None-Match'] === '*' && cur) return mkRes(412, '');
    const etag = '"' + crypto.randomUUID() + '"';
    store.set(key, { body: Buffer.from(init.body), etag });
    return mkRes(200, '', { etag });
  }
  if (u.host === 'ai.api.cloud.yandex.net') {
    return abortable(init.signal, async () => {
      await new Promise(r => setTimeout(r, model.delayMs));
      return mkRes(200, JSON.stringify({ choices: [{ message: { content: JSON.stringify(model.reply) } }] }));
    });
  }
  if (u.host === 'functions.yandexcloud.net') {
    const qp = Object.fromEntries(u.searchParams.entries());
    const event = { httpMethod: init.method || 'GET', queryStringParameters: qp,
      headers: init.headers || {}, body: init.body || '' };
    return abortable(init.signal, async () => {
      const r = await vision.handler(event);
      return mkRes(r.statusCode, r.body);
    });
  }
  throw new Error('unexpected fetch ' + url);
};
vision = require('../moon-vision/index.js');
const skill = require('../moon-skill/index.js');

// Алиса без хранилища: state не возвращается вообще (худший случай живого теста).
let mid = 0;
async function say(text, opts) {
  const o = opts || {};
  const req = { version: '1.0',
    session: { new: !!o.isNew, session_id: o.sid || 's1', message_id: mid++,
      application: { application_id: o.app || 'app-1' } },
    request: { command: text.toLowerCase().replace(/[.,!?]/g, ''), original_utterance: text, type: 'SimpleUtterance' },
    state: o.state || {} };
  return await skill.handler(req);
}
async function postFrame() {
  const jpeg = Buffer.concat([Buffer.from([0xFF, 0xD8, 0xFF, 0xE0]), crypto.randomBytes(64)]);
  const r = await vision.handler({ httpMethod: 'POST', queryStringParameters: { token: 'test-token' },
    headers: { 'content-type': 'application/json' }, body: JSON.stringify({ image: jpeg.toString('base64') }) });
  assert.strictEqual(r.statusCode, 200);
}

test('игра сохраняется, даже если Алиса не возвращает state', async () => {
  store.clear();
  const hi = await say('', { isNew: true });
  assert.match(hi.response.text, /во что играешь/);
  const g = await say('Играю в Cyberpunk 2077');
  assert.strictEqual(g.response.text, 'Принято, игра: Cyberpunk 2077. Спрашивай!');
  assert.strictEqual(g.response.end_session, false);
  assert.strictEqual(g.application_state.game, 'Cyberpunk 2077');
  assert.strictEqual(g.session_state.game, 'Cyberpunk 2077');
  // без кадров: не «назови игру», а честная диагностика экрана, сессия открыта
  const a = await say('Что видишь на экране');
  assert.doesNotMatch(a.response.text, /назови игру/);
  assert.match(a.response.text, /Не вижу экран игры/);
  assert.strictEqual(a.response.end_session, false);
});

test('ответ по кадру использует игру uid, «ну что там» работает без state', async () => {
  store.clear();
  await say('Играю в Cyberpunk 2077');
  await postFrame();
  model.delayMs = 3500; // дольше ASK_TIMEOUT_MS -> filler
  const a = await say('Покажи какая из этих предысторий хорошая для первого прохождения');
  assert.match(a.response.text, /Собираю ответ/);
  assert.strictEqual(a.response.end_session, false);
  await new Promise(r => setTimeout(r, 2500));
  const st = JSON.parse(store.get('state.json').body.toString('utf8'));
  const rec = Object.values(st.answers)[0];
  assert.strictEqual(rec.game, 'Cyberpunk 2077');
  assert.ok(rec.uid);
  const f = await say('Ну что там'); // state не передан
  assert.doesNotMatch(f.response.text, /ничего не собирал/);
  assert.match(f.response.text, /Не удалось выделить|Отметил|предыстори/);
  model.delayMs = 0;
});

test('state Алисы: application_state читается, свежий ts побеждает', async () => {
  store.clear();
  const g = await say('Играю в Ведьмак 3');
  const r = await say('Какая сейчас игра', { state: { application: g.application_state,
    user: { game: 'Старая игра', ts: 1 } } });
  assert.strictEqual(r.response.text, 'Игра: Ведьмак 3.');
});

test('«играю cyberpunk 2077» без предлога: в игру попадает только название', async () => {
  store.clear();
  const g = await say('Играю cyberpunk 2077');
  assert.strictEqual(g.response.text, 'Принято, игра: cyberpunk 2077. Спрашивай!');
  const g2 = await say('Сейчас играю Hitman 3', { state: { application: g.application_state } });
  assert.strictEqual(g2.application_state.game, 'Hitman 3');
  const g3 = await say('Играю с другом в Ведьмак 3', { state: { application: g2.application_state } });
  assert.strictEqual(g3.application_state.game, 'Ведьмак 3');
  // «играю на гитаре» / «играю по сети» — не смена игры
  for (const ph of ['Играю на гитаре', 'Играю по сети с другом']) {
    const r = await say(ph, { state: { application: g3.application_state } });
    assert.doesNotMatch(r.response.text, /Принято, игра/);
    assert.strictEqual(r.application_state.game, 'Ведьмак 3');
  }
});

test('смена игры голым названием и защита вопросов с цифрами', async () => {
  store.clear();
  const g = await say('Играю в Ведьмак 3');
  const sw = await say('Киберпанк 2077', { state: { application: g.application_state } });
  assert.strictEqual(sw.response.text, 'Принято, игра: Киберпанк 2077. Спрашивай!');
  await postFrame();
  const q = await say('Сколько стоит 2 патрона', { state: { application: sw.application_state } });
  assert.doesNotMatch(q.response.text, /Принято, игра/);
});

test('выход закрывает сессию, help — нет', async () => {
  const h = await say('Помощь');
  assert.strictEqual(h.response.end_session, false);
  const e = await say('Хватит');
  assert.strictEqual(e.response.end_session, true);
});

test('бэкенд: answer без qid/cid и без uid по-прежнему none', async () => {
  const r = await vision.handler({ httpMethod: 'GET', queryStringParameters: { token: 'test-token', answer: '1' } });
  assert.strictEqual(JSON.parse(r.body).status, 'none');
});

test('«какая игра» без state берёт игру uid с бэкенда', async () => {
  store.clear();
  await say('Играю в Cyberpunk 2077');
  const r = await say('Какая сейчас игра');
  assert.strictEqual(r.response.text, 'Игра: Cyberpunk 2077.');
  const hi = await say('', { isNew: true });
  assert.match(hi.response.text, /Продолжаем Cyberpunk 2077/);
});

async function api(method, qp, body) {
  const r = await vision.handler({ httpMethod: method, queryStringParameters: Object.assign({ token: 'test-token' }, qp),
    headers: { 'content-type': 'application/json' }, body: body ? JSON.stringify(body) : '' });
  return { code: r.statusCode, body: JSON.parse(r.body) };
}

test('бэкенд: ответ чужого uid по qid не отдаётся', async () => {
  store.clear();
  await postFrame();
  const a = await api('POST', {}, { q: 'что на экране', game: 'X', uid: 'u-aaaaaaaaaaaa' });
  const qid = a.body.qid;
  assert.ok(qid);
  const other = await api('GET', { answer: '1', qid: String(qid), uid: 'u-bbbbbbbbbbbb' });
  assert.strictEqual(other.body.status, 'not_found');
  const mine = await api('GET', { answer: '1', qid: String(qid), uid: 'u-aaaaaaaaaaaa' });
  assert.strictEqual(mine.body.status, 'done');
});

test('бэкенд: ретенция ответов и игроков переживает слияние CAS', async () => {
  store.clear();
  await postFrame();
  for (let i = 0; i < 12; i++) {
    const uid = 'u-' + String(i).padStart(12, '0');
    await api('POST', {}, { game: 'G' + i, uid });
    await api('POST', {}, { q: 'вопрос номер ' + i, game: 'G' + i, uid });
  }
  for (let i = 12; i < 40; i++) await api('POST', {}, { game: 'G' + i, uid: 'u-' + String(i).padStart(12, '0') });
  const st = JSON.parse(store.get('state.json').body.toString('utf8'));
  assert.ok(Object.keys(st.answers).length <= 8, 'answers=' + Object.keys(st.answers).length);
  assert.ok(Object.keys(st.users).length <= 32, 'users=' + Object.keys(st.users).length);
  assert.ok(st.users['u-000000000039']);
});
