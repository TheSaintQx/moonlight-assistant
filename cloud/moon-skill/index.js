'use strict';

const crypto = require('crypto');

// v3.3 (06.11, живой тест v3.2): «играю cyberpunk 2077» без «в» -> игра = только название.
// v3.2 (06.11, разбор живого теста v3.1):
//   S-1 [D-A]: стейт пишется в session_state + application_state + user_state_update
//        (user_state Алиса отдаёт только авторизованным и только при включённом
//        хранилище) и читается из самого свежего (поле ts).
//   S-2 [D-A]: анонимный uid (sha256 user_id|application_id) уходит на бэкенд в
//        ask/game/answer — бэкенд помнит игру игрока, даже если стейт Алисы потерян.
//   S-3 [D-B]: «ну что там» без qid/cid спрашивает бэкенд по uid (последний
//        запрос ЭТОГО игрока); гейт followup учитывает cid.
//   S-4: сессия не закрывается после ответа (было end_session:true -> «Сессия
//        завершена» после каждой реплики); выход — «хватит/стоп/выход».
//   S-5: название игры берётся из original_utterance (регистр «Cyberpunk 2077»);
//        голое название с цифрами меняет игру и при уже выбранной игре.
//   S-6: приветствие укладывается в лимит cleanVoice (3 предложения).

// v3.1 (патчи Алисы 06.11, верифицировано юнит-пробами):
//   F-1: G5 — «игра(?:ю|ешь|ет|ем|ете|ут|ют)? в» (было (?:ем|аю)? — «играю в» НЕ ловился);
//        стоп-лист: порядке? (было порядк[аи]); F-2: G8+G9 одной строкой
//        t.split(/(?<=[.!?…])\s+/) — «2.5» цел, точка сохранена; F-3: ветки
//        409 cid_conflict / superseded и bad_request вместо «Связь подвела»;
//        poll-expired reply несёт статус 'expired' (регрессия G3 снята);
//   F-4: isBareGameTitle принимает кириллицу («Ведьмак 3», «Киберпанк 2077»).
// moonskill v9.6-a-v3 — show marker acknowledgement + bare game title handling.
// База: канонический v9.5.3.1; изменения v2 добавляют CID/grounding-контракт.
// Правки v9.5.3 (Ж-фиксы по ревью Клода, верифицировано Алисой 06.11):
//   G1 (Ж1): «ну что там» при status:'query' — «Ещё собираю», ожидание
//       сохраняется; скользящий TTL с потолком 5 мин (followUpMax/From).
//   G2 (Ж7): FOLLOWUP_RE расширен (вышло/получилось/готово/результат);
//       при живом ожидании короткие реплики (до 3 слов, не show/игра)
//       маршрутизируются в followup до гейта осмысленности.
//   G3 (Ж3): статус в КАЖДЫЙ reply + версия из одной константы VER.
//   G4 (Ж4): isHelp узкий — только команда целиком; «как пользоваться
//       крюком» больше не перехватывается.
//   G5 (Ж5): «играю в X» ловится; стоп-лист «я в ...» (служебные слова:
//       «я в порядке» — не игра).
//   G6 (Ж11): «кто из» с правой границей — «кто изобрёл» больше не show.
//   G7 (Ж10): пустой text при done — отдельный статус done-empty в логе
//       (голосовое «Честно: пока не знаю» сохранено).
//   G8 (микро): cleanVoice не рвёт числа с точкой («2.5») — границей
//       предложения считается знак с пробелом/конец строки.
// Архитектура: навык -> moon-vision (ask/answer/game) -> голос.
// Маркер и кадры — зона приложения FrameCapture, навык не трогает.
// Show-запросы бэкенд всегда анализирует ЗАНОВО.
// v2: ask создаёт CID до сети; follow-up адресуется по qid/CID; старый запрос
//   перед новым помечается superseded; финальный ответ несёт unk/src/frame_id/conf.
// ОГРАНИЧЕНИЯ: в moon-vision v2 отсутствует подключённая проверенная KB/RAG;
//   без свежего кадра backend возвращает unk (кроме уточнения игры).
//   canonicalGame/canonicalEntity и подтверждённая база официальных названий
//   ещё не реализованы; имена разрешены только по читаемому тексту кадра.

const CLOUD_URL = 'https://functions.yandexcloud.net/d4el6hpg7nh0cui52kd2';
// P2-6 (решение директора): резервный литерал убрать ПОСЛЕ ротации
// токена синхронно в навыке и moon-vision.
const TOKEN = process.env.MOON_TOKEN || 'moon-7f3k9p2xq8v4m1w6r5t0zc4';

const TURN_BUDGET_MS    = 4200; // дедлайн хода (запас под лимит платформы)
const SAFETY_MS         = 250;  // запас на сериализацию ответа
const ASK_TIMEOUT_MS    = 2800; // доля бюджета на POST /ask
const ANSWER_TIMEOUT_MS = 1200; // один GET /answer (не больше шага)
const POLL_STEP_MS      = 1200; // шаг опроса
const FOLLOWUP_TTL_MS   = 120000; // сколько живёт «ну что там» после filler
const FOLLOWUP_CEIL_MS  = 300000; // G1: потолок скользящего TTL (5 мин)
const VER               = '9.6-a-v3.3'; // v3.3: «играю X» без предлога
const VOICE_MAX         = 400;

// show-фразы: всегда новый анализ, без кеша (бэкенд тоже так решает)
const SHOW_WORDS = ['покаж', 'обвед', ' кто из ', ' где ']; // G6: правая граница «кто из»
function isShow(utter) {
  const s = ' ' + String(utter || '').toLowerCase() + ' ';
  return SHOW_WORDS.some(w => s.indexOf(w) !== -1);
}

// G2: расширено; фразы-синонимы нормализуются (пунктуация, «ё», пробелы)
function isFollowUp(utter) {
  const s = String(utter || '').toLowerCase().replace(/ё/g, 'е')
    .replace(/[!?,.]/g, '').replace(/\s+/g, ' ').trim();
  if (!s) return false;
  return /^(ну\s+)?(что|ч[е]|как)\s+(там|вышло|получилось|у\s+нас)$/.test(s) ||
    /^(результат|ответ|готово|есть\s+ответ|а\s+что\s+там|ну\s+что)$/.test(s);
}

// «я в X» — эвристика (возможны ложные срабатывания, принимаем честно).
// G5: «играю в X» ловится; стоп-лист служебных слов: «я в порядке» НЕ игра.
const GAME_SET_RES = [
  /^(?:(?:я\s+)?игра(?:ю|ешь|ет|ем|ете|ут|ют)?\s+в|включи игру|запусти игру)\s+(.+)$/i,
  /^запусти лунного[,.!]?\s*(?:я\s+)?в\s+(.+)$/i,
  /^переключи(?:сь)?\s+на\s+(.+)$/i,
  /^я\s+в\s+(.{2,60})$/i,
  // последним: «играю cyberpunk 2077», «играю с другом в X» (хвост чистит gameFrom)
  /^(?:я\s+)?(?:сейчас\s+|сегодня\s+)?игра(?:ю|ем)\s+(.+)$/i
];
const GAME_STOP_RE = /(?:^|\s)(порядке?|себе|курс[е]|шок[е]|отпуск[е]|настроени[ие]|движени[ие]|процесс[е]|форм[е]|ток[е]|восторг[е]|поиск[е]|очеред[и]|замешательств[е]|дел[е]|пут[и]|срок[е])/i;
function isGameSet(utter) {
  const s = String(utter || '').toLowerCase().replace(/ё/g, 'е').trim();
  return GAME_SET_RES.some(re => re.test(s));
}
// Хвост после «играю»: «с другом/с друзьями/вдвоём» отбрасываем, «в» перед
// названием тоже; «на гитаре», «по сети» — не название игры.
const GAME_FILLER_RE = /^(?:с\s+\S+\s+|со\s+\S+\s+|вдвоем\s+|вдвоём\s+|сейчас\s+|сегодня\s+|теперь\s+)+/i;
const GAME_LEAD_PREP_RE = /^(?:на|за|у|по|про|для|из|от|к|ко|до|без|о|об|при|не|с|со)(?:\s|$)/i;
function gameFrom(utter) {
  const s = String(utter || '').trim();
  for (let i = 0; i < GAME_SET_RES.length; i++) {
    const m = s.match(GAME_SET_RES[i]);
    if (!m) continue;
    let g = m[1].replace(/[.!,?]$/, '').trim();
    if (i === GAME_SET_RES.length - 1) { // форма без предлога
      g = g.replace(GAME_FILLER_RE, '').replace(/^в\s+/i, '').trim();
      if (!g || GAME_LEAD_PREP_RE.test(g)) return '';
    }
    g = g.slice(0, 60);
    return GAME_STOP_RE.test(g) ? '' : g; // G5: стоп-лист «я в ...»
  }
  return '';
}

// G4: только команда ЦЕЛИКОМ — игровые вопросы не перехватываются
function isHelp(utter) {
  const s = String(utter || '').toLowerCase().replace(/ё/g, 'е')
    .replace(/[!?,.]/g, '').replace(/\s+/g, ' ').trim()
    .replace(/^алиса,?\s*/, '');
  return /^(помощь|что ты умеешь|какие команды|как пользоваться|команды)$/.test(s);
}
function isExit(utter) {
  const s = String(utter || '').toLowerCase().replace(/ё/g, 'е')
    .replace(/[!?,.]/g, '').replace(/\s+/g, ' ').trim();
  return /^(хватит|стоп|выход|выйти|закончить|заверши(ть)?( навык)?|пока|до свидания|спасибо пока)$/.test(s);
}
function isGameInfo(utter) {
  const s = String(utter || '').toLowerCase().replace(/ё/g, 'е');
  return /какая (сейчас )?игра|во что (мы )?играем|во что играешь|какая у нас игра/.test(s);
}

// Без слов «я в» принимаем только безопасную форму названия с цифрами
// (например, Cyberpunk 2077), если игра ещё не выбрана.
const QUESTION_START_RE = /^(что|как|где|кто|сколько|какой|какая|какое|какие|каких|почему|зачем|когда|куда|откуда|чем|чей|можно|есть|дай|скажи|расскажи|найди|покажи|обведи)(\s|$)/i;
function normGame(s) {
  return String(s || '').toLowerCase().replace(/ё/g, 'е').replace(/[^a-zа-я0-9]+/g, ' ').trim();
}
function isBareGameTitle(utter, currentGame) {
  const s = String(utter || '').trim().replace(/[.!]$/, '');
  if (!/^[A-Za-zА-Яа-яЁё][A-Za-zА-Яа-яЁё0-9 .:'-]{1,58}$/.test(s)) return false; // F-4: + кириллица («Ведьмак 3»)
  if (!/\d/.test(s) || QUESTION_START_RE.test(s)) return false;
  // S-5: то же название повторно — не смена игры, но и не вопрос: подтверждаем.
  if (currentGame && normGame(currentGame) === normGame(s)) return true;
  const parts = s.split(/\s+/);
  return parts.length >= 2 && parts.length <= 4;
}

// F6: markdown и ссылки наружу, 1-3 предложения, VOICE_MAX
function cleanVoice(text) {
  let t = String(text || '')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/[*_`#>~]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  const sents = t.split(/(?<=[.!?…])\s+/).map(s => s.trim()).filter(Boolean); // G8+G9: lookbehind — «2.5» цел, точка сохранена
  t = sents.slice(0, 3).join(' ');
  if (t.length <= VOICE_MAX) return t;
  const cut = t.slice(0, VOICE_MAX - 1);
  return cut.replace(/\s+\S*$/, '').trim() + '…';
}

// kind: 'ok' | 'http' | 'timeout' | 'network'
async function cloudFetch(path, init, timeoutMs) {
  try {
    const res = await fetch(CLOUD_URL + path, {
      ...init,
      signal: AbortSignal.timeout(timeoutMs)
    });
    const text = await res.text();
    let body = null;
    try { body = JSON.parse(text); } catch (e) { body = null; }
    return { status: res.status, body, kind: res.status === 200 ? 'ok' : 'http' };
  } catch (e) {
    const kind = (e && e.name === 'TimeoutError') ? 'timeout' : 'network';
    return { status: 0, body: null, kind };
  }
}

// Детерминированный CID: после ask-timeout навык всё равно знает, какой ответ забирать.
function makeCid(session, utter) {
  const sid = String((session && session.session_id) || '');
  const hasMessageId = session && session.message_id !== undefined && session.message_id !== null;
  const mid = hasMessageId ? String(session.message_id) : '';
  // Если платформа не прислала идентификаторы, избегаем CID-коллизий ценой отсутствия retry-idempotency.
  const nonce = sid && hasMessageId ? sid + ':' + mid : String(Date.now()) + ':' + String(Math.random());
  const digest = crypto.createHash('sha256').update(nonce + ':' + String(utter || '')).digest('hex');
  return 'c-' + digest.slice(0, 24);
}

// POST /ask: CID делает повтор запроса идемпотентным; supersede заменяет прежнее ожидание.
async function ask(q, game, cid, supersede, timeoutMs, uid) {
  const payload = { q: String(q || '').slice(0, 200), cid: String(cid || '').slice(0, 64) };
  if (game) payload.game = String(game).slice(0, 60);
  if (uid) payload.uid = uid;
  if (supersede) payload.supersede = String(supersede).slice(0, 64);
  return await cloudFetch('?token=' + encodeURIComponent(TOKEN), {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  }, Math.max(200, timeoutMs));
}

// GET /answer: конкретный qid/CID; без них — последний запрос ЭТОГО uid (S-3).
// Глобальный «самый свежий» не используется.
async function answer(qid, cid, timeoutMs, uid) {
  const p = '?token=' + encodeURIComponent(TOKEN) + '&answer=1' +
    (qid ? '&qid=' + encodeURIComponent(qid) : '') +
    (cid ? '&cid=' + encodeURIComponent(cid) : '') +
    (uid ? '&uid=' + encodeURIComponent(uid) : '');
  return await cloudFetch(p, { method: 'GET' },
    Math.max(150, Math.min(ANSWER_TIMEOUT_MS, timeoutMs || ANSWER_TIMEOUT_MS)));
}

// F2: setGame больше не 5000 — только остаток бюджета (кап 1200).
async function setGame(game, supersede, timeoutMs, uid) {
  const payload = { game: String(game).slice(0, 60) };
  if (uid) payload.uid = uid;
  if (supersede) payload.supersede = String(supersede).slice(0, 64);
  return await cloudFetch('?token=' + encodeURIComponent(TOKEN), {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  }, Math.max(150, Math.min(1200, timeoutMs || 1200)));
}

// Игра этого игрока с бэкенда (если Алиса не вернула стейт).
async function fetchUserGame(uid, timeoutMs) {
  if (!uid) return '';
  const r = await cloudFetch('?token=' + encodeURIComponent(TOKEN) + '&usergame=1&uid=' +
    encodeURIComponent(uid), { method: 'GET' }, timeoutMs || 800);
  return (r.kind === 'ok' && r.body && typeof r.body.game === 'string') ? r.body.game.slice(0, 60) : '';
}

// F4: атомарная очистка ожидания (G1: вместе с окнами скользящего TTL)
function clearPending(s) {
  s.qid = 0; s.cid = ''; s.followUpUntil = 0; s.followUpMax = 0; s.followUpFrom = 0;
}
// G1: скользящее продление ожидания; потолок 5 мин от старта сбора
function slideFollowUp(s) {
  if (!s.followUpMax || !s.followUpFrom) {
    s.followUpMax = Date.now() + FOLLOWUP_CEIL_MS;
    s.followUpFrom = Date.now();
  }
  s.followUpUntil = Math.min(Date.now() + FOLLOWUP_TTL_MS, s.followUpMax);
}
// G1: «N секунд/секунды/секунда» для честной реплики «прошло N»
function pluralSec(n) {
  const a = Math.abs(n) % 100, b = a % 10;
  if (a > 10 && a < 20) return 'секунд';
  if (b >= 2 && b <= 4) return 'секунды';
  return n === 1 ? 'секунда' : 'секунд';
}

// F2+F3: опрос в пределах дедлайна; сон и после сбоя — без шторма.
async function pollAnswer(qid, cid, untilMs, uid) {
  while (Date.now() < untilMs) {
    const remaining = untilMs - Date.now() - SAFETY_MS;
    if (remaining < 150) return null;
    const r = await answer(qid, cid, Math.min(1200, remaining), uid);
    if (r.kind !== 'ok') {
      if (Date.now() + POLL_STEP_MS > untilMs) return null;
      await new Promise(res => setTimeout(res, POLL_STEP_MS));
      continue;
    }
    if (r.body && (r.body.status === 'done' || r.body.status === 'clarify')) {
      return { text: String(r.body.text || ''), clarify: r.body.status === 'clarify', meta: r.body };
    }
    if (r.body && (r.body.status === 'expired' || r.body.status === 'superseded' || r.body.status === 'cancelled')) {
      return { expired: true };
    }
    if (Date.now() + POLL_STEP_MS > untilMs) return null;
    await new Promise(res => setTimeout(res, POLL_STEP_MS));
  }
  return null;
}

// S-1: стейт Алисы — три канала. session_state живёт в сессии,
// application_state — на устройстве (работает без авторизации),
// user_state_update — у авторизованного пользователя. Читаем самый свежий по ts.
function loadState(req) {
  const st = (req && req.state) || {};
  let best = null;
  for (const c of [st.session, st.application, st.user]) {
    if (!c || typeof c !== 'object' || !Object.keys(c).length) continue;
    if (!best || (Number(c.ts) || 0) > (Number(best.ts) || 0)) best = c;
  }
  return best || {};
}
// S-2: анонимный идентификатор игрока для бэкенда (сырые id наружу не уходят).
// Секретная соль (env MOON_UID_SALT): без неё uid выводится из user_id Алисы.
const UID_SALT = process.env.MOON_UID_SALT || 'moon:';
function makeUid(session) {
  const s = session || {};
  const raw = (s.user && s.user.user_id) || (s.application && s.application.application_id) ||
    s.user_id || '';
  if (!raw) return '';
  return 'u-' + crypto.createHash('sha256').update(UID_SALT + String(raw)).digest('hex').slice(0, 24);
}

// Эхо session обязателен
function resp(text, session, endSession, userState) {
  const out = {
    version: '1.0',
    session: session || {},
    response: {
      text: String(text || '').slice(0, 1024),
      tts: String(text || '').slice(0, 1024),
      end_session: !!endSession
    }
  };
  if (userState) {
    const snap = Object.assign({}, userState, { ts: Date.now() });
    out.session_state = snap;
    out.application_state = snap;
    out.user_state_update = snap;
  }
  return out;
}

module.exports.handler = async function (event) {
  const t0 = Date.now();
  let req = event;
  try {
    if (event && event.body) {
      req = typeof event.body === 'string' ? JSON.parse(event.body) : event.body;
    }
  } catch (e) { req = event; }

  const session = (req && req.session) || {};
  const utter = String((req && req.request &&
    (req.request.command || req.request.original_utterance)) || '').trim();
  const origUtter = String((req && req.request && req.request.original_utterance) || utter).trim();
  const user = loadState(req);
  const uid = makeUid(session);
  const uState = {
    game: String(user.game || '').slice(0, 60),
    qid: user.qid || 0,
    cid: String(user.cid || '').slice(0, 64),
    followUpUntil: user.followUpUntil || 0,
    followUpMax: user.followUpMax || 0,   // G1: потолок скользящего TTL
    followUpFrom: user.followUpFrom || 0  // G1: когда начали собирать
  };
  const turnDeadline = t0 + TURN_BUDGET_MS;

  // F8: маршрут копится в finalRoute; ОДИН итоговый лог на ход.
  let finalRoute = 'unknown';
  const route = (name) => { finalRoute = name; };
  const reply = (text, endSession, status, meta) => {
    const v = cleanVoice(text);
    const m = meta || {};
    console.log(JSON.stringify({ s: 'moon', v: VER, route: finalRoute,
      status: status || 'ok', ms: Date.now() - t0, end: !!endSession,
      hasGame: !!uState.game, hasQid: !!uState.qid, hasUid: !!uid,
      unk: m.unk === true, src: m.src || 'none', frame_id: m.frame_id || null,
      conf: typeof m.conf === 'number' ? m.conf : null }));
    return resp(v, session, endSession, uState);
  };

  // Стейт Алисы потерян: игру для приветствия и «какая игра» берём с бэкенда по uid.
  if (!uState.game && uid && ((session.new && !utter) || isGameInfo(utter))) {
    uState.game = await fetchUserGame(uid, 800);
  }

  // старт навыка: приветствие только на пустом старте
  if (session.new && !utter) {
    route('start');
    return reply(uState.game
      ? 'Привет! Продолжаем ' + uState.game + ' — задавай вопросы, а по слову «покажи» обведу объект на экране.'
      : 'Привет! Я Лунный Ассистент: скажи, во что играешь, и задавай вопросы. По слову «покажи» обведу объект на экране.', false, 'ok');
  }

  if (isExit(utter)) {
    route('exit');
    return reply('До встречи! Удачной игры.', true, 'ok');
  }

  if (isHelp(utter)) {
    route('help');
    return reply('Скажи, во что играешь, и задавай вопросы. «Покажи воина» — обведу объект на экране. «Ну что там» — забрать ответ, если я сказал «собираю».', false, 'ok');
  }

  if (isGameInfo(utter)) {
    route('gameinfo');
    return reply(uState.game ? 'Игра: ' + uState.game + '.' :
      'Игра пока не названа. Скажи, например, «я в Ведьмаке».', false, 'ok');
  }

  // Ж1/Ж7 (G1/G2): «ну что там» и синонимы — добор отложенного ответа.
  // TTL проверяем ДО запроса; при query — скользящий TTL, ожидание живёт.
  const doFollowUp = async () => {
    const hasLocal = !!(uState.followUpUntil || uState.qid || uState.cid);
    if (!hasLocal && !uid) {
      route('followup-none');
      return reply('Я пока ничего не собирал. Задай вопрос.', false, 'none');
    }
    if (uState.followUpUntil && Date.now() > uState.followUpUntil) {
      route('followup-ttl');
      clearPending(uState);
      return reply('Ответ не успел собраться. Спроси ещё раз.', false, 'expired');
    }
    // S-3: локального ожидания нет (стейт Алисы потерян) — бэкенд ищет по uid.
    const r = await answer(uState.qid || 0, uState.cid,
      turnDeadline - Date.now() - SAFETY_MS, uid);
    if (r.body && (r.body.status === 'done' || r.body.status === 'clarify')) {
      route(r.body.status === 'clarify' ? 'followup-clarify' : 'followup-done');
      clearPending(uState);
      const t = String(r.body.text || '');
      return reply(t ? t : 'Честно: пока не знаю. Спроси иначе.', false,
        r.body.status === 'clarify' ? 'clarify' : (t ? 'done' : 'done-empty'), r.body);
    }
    if (r.body && (r.body.status === 'superseded' || r.body.status === 'cancelled')) {
      route('followup-superseded');
      clearPending(uState);
      return reply('Этот запрос уже заменён новым. Скажи «ну что там» после нового вопроса.', false, 'superseded');
    }
    if (r.body && r.body.status === 'none') {
      route('followup-none');
      clearPending(uState);
      return reply(hasLocal ? 'Не нашёл запрос, который нужно проверить. Задай вопрос ещё раз.'
        : 'Я пока ничего не собирал. Задай вопрос.', false, 'none');
    }
    if (r.body && r.body.status === 'expired') {
      route('followup-expired');
      clearPending(uState);
      return reply('Ответ не успел собраться. Спроси ещё раз.', false, 'expired');
    }
    if (r.kind === 'network' || r.kind === 'http') {
      route('followup-network');
      return reply('Связь с сервером подвела. Скажи «ну что там» ещё раз.', false, 'network');
    }
    if (r.kind === 'timeout') {
      route('followup-wait');
      slideFollowUp(uState);
      return reply('Ещё собираю. Скажи «ну что там» через минуту.', false, 'wait');
    }
    // G1 (Ж1): 200 и статус query (или иная валидная структура) — модель
    // ЕЩЁ ДУМАЕТ: не protocol, ожидание сохраняем, TTL скользит до 5 мин.
    route('followup-pending');
    slideFollowUp(uState);
    const seen = uState.followUpFrom ?
      Math.round((Date.now() - uState.followUpFrom) / 1000) : 0;
    if (!seen) return reply('Ещё собираю. Скажи «ну что там» через минуту.', false, 'pending');
    return reply('Ещё собираю, прошло около ' + seen + ' ' + pluralSec(seen) +
      '. Скажи «ну что там» позже.', false, 'pending');
  };

  if (isFollowUp(utter)) {
    return await doFollowUp();
  }
  // G2 (Ж7): живое ожидание + короткая реплика (не show/не установка игры)
  // — тоже добор: «ну», «а ну», «есть?», «чё там» и прочие до трёх слов.
  const words = utter.split(/\s+/).filter(Boolean);
  const pendAlive = !!uState.qid || Date.now() < uState.followUpUntil;
  if (pendAlive && words.length <= 3 && !isShow(utter) && !isGameSet(utter) &&
      !isBareGameTitle(utter, uState.game)) {
    route('followup-short');
    return await doFollowUp();
  }

  // установка игры. F4: смена игры чистит старое ожидание. F2: остаток бюджета.
  if ((isGameSet(utter) || isBareGameTitle(utter, uState.game)) && !isShow(utter)) { // F10: show важнее
    // S-5: название — из original_utterance (Алиса приводит command к нижнему регистру)
    const g = isGameSet(utter)
      ? (gameFrom(origUtter) || gameFrom(utter))
      : (isBareGameTitle(origUtter, uState.game) ? origUtter : utter).trim().replace(/[.!]$/, '').slice(0, 60);
    if (g) {
      route('setgame');
      const oldCid = uState.cid || (uState.qid ? String(uState.qid) : '');
      clearPending(uState);
      uState.game = g;
      const sg = await setGame(g, oldCid, turnDeadline - Date.now() - SAFETY_MS, uid);
      if (sg.status === 200) {
        return reply('Принято, игра: ' + g + '. Спрашивай!', false, 'ok');
      }
      return reply('Записал у себя: ' + g + '. Бэкенд не подтвердил — если что-то пойдёт не так, назови игру ещё раз.', false, 'degraded');
    }
  }

  // короткая болтовня без смысла — подсказка, не отказ
  const meaningful = words.length >= 2 || isShow(utter);
  if (!meaningful) {
    route('short');
    return reply('Скажи вопрос целиком, или скажи «ну что там».', false, 'short');
  }

  // Основной путь. Новый вопрос получает CID ДО сети; прежнее ожидание отправляется в supersede.
  const oldCid = uState.cid || (uState.qid ? String(uState.qid) : '');
  const cid = makeCid(session, utter);
  clearPending(uState);
  uState.cid = cid;
  const r = await ask(utter, uState.game, cid, oldCid,
    Math.min(ASK_TIMEOUT_MS, turnDeadline - Date.now() - SAFETY_MS), uid);

  if (r.body && (r.body.status === 'done' || r.body.status === 'clarify')) {
    route(r.body.status === 'clarify' ? 'ask-clarify' : 'ask-done');
    clearPending(uState);
    const t = String(r.body.text || '');
    return reply(t ? t : 'Честно: пока не знаю. Спроси иначе.', false,
      r.body.status === 'clarify' ? 'clarify' : (t ? 'done' : 'done-empty'), r.body);
  }
  if (r.body && (r.body.status === 'superseded' || r.body.status === 'cancelled' || r.body.status === 'expired')) {
    route('ask-terminal');
    clearPending(uState);
    return reply('Запрос завершён до получения ответа. Задай вопрос ещё раз.', false, r.body.status);
  }
  if (r.body && r.body.status === 'query' && r.body.qid) {
    uState.qid = r.body.qid;
    const got = await pollAnswer(r.body.qid, cid, turnDeadline - SAFETY_MS, uid);
    if (got && got.text) {
      route(got.clarify ? 'poll-clarify' : 'poll-done');
      clearPending(uState);
      return reply(got.text, false, got.clarify ? 'clarify' : 'done', got.meta);
    }
    if (got && got.expired) {
      route('poll-expired');
      clearPending(uState);
      return reply('Ответ не успел собраться или запрос был заменён. Спроси ещё раз.', false, 'expired');
    }
    route('poll-filler');
    slideFollowUp(uState);
    return reply('Собираю ответ. Через минуту скажи «ну что там».', false, 'pending');
  }
  if (r.kind === 'timeout') {
    // CID уже сохранён: последующий GET найдёт именно этот запрос после ask-timeout.
    route('ask-timeout-filler');
    slideFollowUp(uState);
    return reply('Собираю ответ. Через минуту скажи «ну что там».', false, 'pending');
  }
  if (r.kind === 'ok') {
    // F9: 200, но JSON нестандартный — protocol -> filler с followUp
    route('ask-protocol');
    slideFollowUp(uState);
    return reply('Собираю ответ. Через минуту скажи «ну что там».', false, 'protocol');
  }
  if (r.kind === 'http' && r.body && (r.body.status === 'cid_conflict' || r.body.status === 'superseded')) {
    // F-3: 409 — вопрос уже обрабатывается бэкендом, это НЕ сетевой сбой
    route('ask-cid-conflict');
    slideFollowUp(uState);
    return reply('Этот вопрос уже собирается. Через минуту скажи «ну что там».', false, 'cid-conflict');
  }
  if (r.kind === 'http' && r.body && r.body.status === 'bad_request') {
    route('ask-bad-request');
    return reply('Скажи вопрос целиком, я не поняла.', false, 'bad-request');
  }
  // сетевой сбой / http-ошибка: запрос, скорее всего, не дошёл — честно
  route('ask-network');
  return reply('Связь с сервером подвела. Скажи вопрос ещё раз.', false, 'network');
};
