// MemoriesBK — version navigateur. Aucune dépendance : servi tel quel par l'API (server/api.py).
import { hashFile } from "./sha256.js";

const $app = document.getElementById("app");
const TOKEN_KEY = "mbk_token";
let token = null;
try { token = localStorage.getItem(TOKEN_KEY); } catch { /* stockage indisponible : reconnexion à chaque visite */ }

// ---------- Langue ----------

const LANG_KEY = "mbk_lang";
// Clés suffixées _one/_other : pluriel choisi par Intl.PluralRules (le japonais n'a que _other)
const STR = {
  fr: {
    session_expired: "Session expirée, reconnecte-toi",
    error_status: "Erreur {status}",
    language: "Langue",
    search: "Rechercher",
    search_placeholder: "Rechercher : « plage au coucher du soleil »",
    tab_photos: "Photos",
    tab_import: "Importer",
    logout: "Se déconnecter",
    confirm_logout: "Se déconnecter de MemoriesBK ?",
    login_intro: "Connecte-toi avec ton compte.",
    login_user: "Identifiant",
    login_password: "Mot de passe",
    login_invalid: "Identifiants invalides",
    login_submit: "Se connecter",
    alt_video: "Vidéo du {date}",
    alt_photo: "Photo du {date}",
    photos_empty: "Aucune photo pour l'instant : utilise l'onglet Importer ou l'app Android.",
    searching: "Recherche…",
    search_results: "Résultats pour « {q} »",
    no_results: "Aucun résultat.",
    search_starting: "La recherche démarre sur le serveur, réessaie dans une minute.",
    viewer: "Visionneuse",
    close: "Fermer",
    download_original: "Télécharger l'original",
    info: "Infos",
    info_hide: "Masquer les infos",
    prev: "Précédente",
    next: "Suivante",
    loading: "Chargement…",
    size_kb: "{v} Ko",
    size_mb: "{v} Mo",
    info_place: "Lieu",
    info_coords: "Coordonnées",
    info_camera: "Appareil",
    info_file: "Fichier",
    info_dims: "Dimensions",
    info_ocr: "Texte détecté",
    info_people: "Personnes",
    unnamed: "Sans nom",
    info_tags: "Mots-clés",
    import_title: "Importer des photos",
    import_intro: "Choisis la source. Les doublons (même contenu, même s'ils ont été renommés) sont ignorés ; les fichiers d'origine ne sont jamais modifiés.",
    summary: "{done}/{total} traités — {up} envoyés, {ex} déjà présents, {fail} échecs",
    ignored_one: ", {n} fichier non-média ignoré",
    ignored_other: ", {n} fichiers non-médias ignorés",
    q_pending: "En attente",
    q_hash: "Analyse {p} %",
    q_upload: "Envoi {p} %",
    q_exists: "Déjà présent",
    q_sent: "Envoyé ✓",
    q_failed: "Échec : {msg}",
    upload_conflict: "conflit d'envoi persistant",
    drop: "Glisse ici des photos, des vidéos ou des dossiers entiers",
    from_computer: "Depuis cet ordinateur",
    computer_intro: "Envoi par blocs : une coupure reprend là où elle s'était arrêtée. Garde cette page ouverte pendant l'envoi.",
    choose_files: "Choisir des fichiers",
    choose_folder: "Choisir un dossier",
    job_queued: "En attente",
    job_running: "En cours…",
    job_done: "Terminé",
    job_error: "Erreur",
    location: "Emplacement",
    import_folder: "Importer ce dossier",
    no_subfolders: "Aucun sous-dossier",
    media_in_folder_one: "{n} photo ou vidéo directement dans ce dossier (les sous-dossiers sont inclus à l'import).",
    media_in_folder_other: "{n} photos ou vidéos directement dans ce dossier (les sous-dossiers sont inclus à l'import).",
    import_named: "Importer « {name} »",
    import_all: "Importer tout le NAS",
    whole_nas: "tout le NAS",
    confirm_import: "Importer « {path} » et tous ses sous-dossiers dans ta bibliothèque ?",
    import_started: "Import lancé sur le serveur",
    no_imports: "Aucun import pour l'instant.",
    from_nas: "Depuis le NAS",
    nas_intro: "Parcours le partage du NAS et choisis un dossier. L'import se fait sur le serveur : tu peux fermer cette page.",
    imports: "Imports",
    th_folder: "Dossier",
    th_status: "État",
    th_found: "Trouvés",
    th_imported: "Importés",
    th_skipped: "Déjà là",
    th_failed: "Échecs",
  },
  ja: {
    session_expired: "セッションの有効期限が切れました。もう一度ログインしてください",
    error_status: "エラー {status}",
    language: "言語",
    search: "検索",
    search_placeholder: "検索：「夕焼けのビーチ」",
    tab_photos: "フォト",
    tab_import: "インポート",
    logout: "ログアウト",
    confirm_logout: "MemoriesBK からログアウトしますか？",
    login_intro: "アカウントでログインしてください。",
    login_user: "ユーザー名",
    login_password: "パスワード",
    login_invalid: "ユーザー名またはパスワードが正しくありません",
    login_submit: "ログイン",
    alt_video: "{date}の動画",
    alt_photo: "{date}の写真",
    photos_empty: "まだ写真がありません。「インポート」タブか Android アプリから追加してください。",
    searching: "検索中…",
    search_results: "「{q}」の検索結果",
    no_results: "見つかりませんでした。",
    search_starting: "サーバーで検索を準備中です。1 分ほどしてからもう一度お試しください。",
    viewer: "ビューア",
    close: "閉じる",
    download_original: "元のファイルをダウンロード",
    info: "情報",
    info_hide: "情報を隠す",
    prev: "前へ",
    next: "次へ",
    loading: "読み込み中…",
    size_kb: "{v} KB",
    size_mb: "{v} MB",
    info_place: "撮影地",
    info_coords: "座標",
    info_camera: "カメラ",
    info_file: "ファイル",
    info_dims: "サイズ",
    info_ocr: "検出されたテキスト",
    info_people: "人物",
    unnamed: "名前なし",
    info_tags: "キーワード",
    import_title: "写真をインポート",
    import_intro: "インポート元を選んでください。重複ファイル（名前が違っても内容が同じもの）はスキップされます。元のファイルが変更されることはありません。",
    summary: "{done}/{total} 件処理済み — アップロード {up} 件、登録済み {ex} 件、失敗 {fail} 件",
    ignored_other: "、メディア以外の {n} 件をスキップ",
    q_pending: "待機中",
    q_hash: "解析中 {p}%",
    q_upload: "アップロード中 {p}%",
    q_exists: "登録済み",
    q_sent: "アップロード完了 ✓",
    q_failed: "失敗：{msg}",
    upload_conflict: "アップロードの競合が解消されません",
    drop: "写真、動画、フォルダをここにドラッグ＆ドロップ",
    from_computer: "このパソコンから",
    computer_intro: "分割してアップロードするので、接続が切れても続きから再開できます。アップロード中はこのページを開いたままにしてください。",
    choose_files: "ファイルを選択",
    choose_folder: "フォルダを選択",
    job_queued: "待機中",
    job_running: "実行中…",
    job_done: "完了",
    job_error: "エラー",
    location: "場所",
    import_folder: "このフォルダをインポート",
    no_subfolders: "サブフォルダはありません",
    media_in_folder_other: "このフォルダ直下に写真・動画が {n} 件あります（インポート時はサブフォルダも含まれます）。",
    import_named: "「{name}」をインポート",
    import_all: "NAS 全体をインポート",
    whole_nas: "NAS 全体",
    confirm_import: "「{path}」とそのサブフォルダをすべてライブラリにインポートしますか？",
    import_started: "サーバーでインポートを開始しました",
    no_imports: "インポート履歴はまだありません。",
    from_nas: "NAS から",
    nas_intro: "NAS の共有フォルダを参照してフォルダを選んでください。インポートはサーバーで行われるので、このページを閉じても大丈夫です。",
    imports: "インポート履歴",
    th_folder: "フォルダ",
    th_status: "状態",
    th_found: "検出",
    th_imported: "インポート済み",
    th_skipped: "登録済み",
    th_failed: "失敗",
    // Catégories IA : la clé française reste celle du serveur, seul l'affichage change
    "cat:Plage": "ビーチ", "cat:Montagne": "山", "cat:Nature": "自然", "cat:Ville": "街", "cat:Nourriture": "料理",
    "cat:Animaux": "動物", "cat:Bébés et enfants": "赤ちゃん・子ども", "cat:Selfies": "セルフィー", "cat:Groupes": "グループ",
    "cat:Fêtes": "パーティー", "cat:Neige": "雪", "cat:Nuit": "夜", "cat:Voitures": "車", "cat:Documents": "書類",
    "cat:Captures d'écran": "スクリーンショット",
  },
};

let lang = null;
try { lang = localStorage.getItem(LANG_KEY); } catch { /* stockage indisponible */ }
if (!STR[lang]) lang = (navigator.language || "").toLowerCase().startsWith("ja") ? "ja" : "fr";

/** Texte traduit ; {nom} remplacé par params.nom, params.n choisit le pluriel. */
function t(key, params = {}) {
  const d = STR[lang];
  const s = ("n" in params ? d[`${key}_${new Intl.PluralRules(lang).select(params.n)}`] : undefined) ?? d[key] ?? STR.fr[key] ?? key;
  return s.replace(/\{(\w+)\}/g, (m, k) => params[k] ?? m);
}
const tagLabel = (tag) => STR[lang][`cat:${tag}`] ?? tag;

// ---------- Utilitaires ----------

/** Crée un élément DOM ; le texte passe toujours par textContent (pas d'injection HTML). */
function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "class") el.className = v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children.flat()) if (c != null && c !== false) el.append(c instanceof Node ? c : String(c));
  return el;
}

function setToken(t) {
  token = t;
  try { t ? localStorage.setItem(TOKEN_KEY, t) : localStorage.removeItem(TOKEN_KEY); } catch { /* idem */ }
  // Cookie lu par le serveur uniquement pour les GET (<img>, <video>, téléchargement)
  document.cookie = t ? `mbk=${t}; path=/; max-age=${90 * 86400}; SameSite=Strict` : "mbk=; path=/; max-age=0; SameSite=Strict";
}

async function api(method, path, body) {
  const r = await fetch(path, {
    method,
    headers: { Authorization: `Bearer ${token}`, ...(body ? { "Content-Type": "application/json" } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (r.status === 401) { setToken(null); render(); throw new Error(t("session_expired")); }
  if (!r.ok) {
    let msg = t("error_status", { status: r.status });
    try { const j = await r.json(); msg = typeof j.detail === "string" ? j.detail : j.detail?.message || msg; } catch { /* corps non JSON */ }
    throw Object.assign(new Error(msg), { status: r.status });
  }
  return r.status === 204 ? null : r.json();
}

const sleep = (ms) => new Promise((res) => setTimeout(res, ms));
const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);
// taken_at = heure locale murale encodée en UTC (convention serveur) : on formate donc en UTC
let dayFmt, fullFmt;
function applyLang() {
  const locale = lang === "ja" ? "ja-JP" : "fr-FR";
  document.documentElement.lang = lang;
  dayFmt = new Intl.DateTimeFormat(locale, { timeZone: "UTC", weekday: "long", day: "numeric", month: "long", year: "numeric" });
  fullFmt = new Intl.DateTimeFormat(locale, { timeZone: "UTC", dateStyle: "full", timeStyle: "short" });
}
applyLang();

function setLang(l) {
  lang = l;
  try { localStorage.setItem(LANG_KEY, l); } catch { /* idem */ }
  applyLang();
  if (computerCardEl && !computerCardEl.busy()) computerCardEl = null; // reconstruit dans la nouvelle langue s'il n'y a pas d'envoi en cours
  render();
}

const langSwitch = () => h("span", { class: "lang", role: "group", "aria-label": t("language") },
  [["fr", "FR"], ["ja", "日本語"]].map(([l, label]) =>
    h("button", { class: "link", type: "button", lang: l, "aria-pressed": String(lang === l), onclick: () => setLang(l) }, label)));

function toast(msg) {
  const t = h("div", { class: "toast", role: "status" }, msg);
  document.body.append(t);
  setTimeout(() => t.remove(), 3500);
}

// ---------- Rendu et navigation ----------

const route = () => (location.hash.slice(2) || "photos").split("?")[0];
const searchQuery = () => new URLSearchParams(location.hash.split("?")[1] || "").get("q") || "";
window.addEventListener("hashchange", render);

function render() {
  $app.replaceChildren();
  if (!token) return renderLogin();
  const page = h("div", { class: "page" });
  $app.append(topbar(), page);
  ({ photos: renderPhotos, search: renderSearch, import: renderImport }[route()] || renderPhotos)(page);
}

function topbar() {
  const r = route();
  const tab = (id, label) => h("a", { href: `#/${id}`, class: r === id ? "active" : "" }, label);
  const q = h("input", {
    class: "search", type: "search", value: searchQuery(), "aria-label": t("search"),
    placeholder: t("search_placeholder"),
    onkeydown: (e) => { if (e.key === "Enter" && q.value.trim()) location.hash = `#/search?q=${encodeURIComponent(q.value.trim())}`; },
  });
  return h("header", { class: "topbar" },
    h("span", { class: "brand" }, "MemoriesBK"),
    h("nav", { class: "tabs" }, tab("photos", t("tab_photos")), tab("import", t("tab_import"))),
    q,
    langSwitch(),
    h("button", { class: "link", onclick: () => { if (confirm(t("confirm_logout"))) { setToken(null); render(); } } }, t("logout")));
}

function renderLogin() {
  const err = h("p", { class: "error", role: "alert" });
  const email = h("input", { type: "text", required: true, autocomplete: "username", autocapitalize: "none" });
  const pw = h("input", { type: "password", required: true, autocomplete: "current-password" });
  $app.append(h("form", {
    class: "login",
    onsubmit: async (e) => {
      e.preventDefault();
      err.textContent = "";
      try {
        const r = await fetch("/api/v1/auth/login", {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ email: email.value.trim(), password: pw.value }),
        });
        if (!r.ok) throw new Error(r.status === 401 ? t("login_invalid") : t("error_status", { status: r.status }));
        setToken((await r.json()).access_token);
        render();
      } catch (ex) { err.textContent = ex.message; }
    },
  },
  langSwitch(),
  h("h1", {}, "MemoriesBK"),
  h("p", { class: "muted" }, t("login_intro")),
  h("label", { class: "field" }, t("login_user"), email),
  h("label", { class: "field" }, t("login_password"), pw),
  err,
  h("button", { class: "btn primary", type: "submit" }, t("login_submit"))));
}

function thumb(it, onclick) {
  return h("button", { class: "thumb", onclick, title: cap(fullFmt.format(new Date(it.taken_at))) },
    h("img", { src: `/api/v1/media/${it.id}/thumb/sm`, loading: "lazy", alt: t(it.kind === "video" ? "alt_video" : "alt_photo", { date: dayFmt.format(new Date(it.taken_at)) }) }),
    it.kind === "video" && h("span", { class: "play", "aria-hidden": "true" }, "▶"));
}

// ---------- Photos : timeline infinie groupée par jour ----------

function renderPhotos(page) {
  const items = [];
  let cursor = null, done = false, loading = false, lastDay = null, grid = null;
  const sentinel = h("div", { class: "sentinel" });
  page.append(sentinel);

  async function more() {
    if (loading || done) return;
    loading = true;
    try {
      const res = await api("GET", `/api/v1/media/timeline?limit=200${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`);
      for (const it of res.items) {
        const day = cap(dayFmt.format(new Date(it.taken_at)));
        if (day !== lastDay) {
          lastDay = day;
          grid = h("div", { class: "grid" });
          page.insertBefore(h("h2", { class: "section" }, day), sentinel);
          page.insertBefore(grid, sentinel);
        }
        const index = items.push(it) - 1;
        grid.append(thumb(it, () => openViewer(items, index)));
      }
      cursor = res.next_cursor;
      done = !cursor;
      if (!items.length) page.insertBefore(h("p", { class: "muted" }, t("photos_empty")), sentinel);
    } catch (e) { toast(e.message); done = true; } finally { loading = false; }
    if (!done && sentinel.getBoundingClientRect().top < innerHeight + 800) more(); // remplir un grand écran
  }
  new IntersectionObserver((es) => es[0].isIntersecting && more(), { rootMargin: "800px" }).observe(sentinel);
}

// ---------- Recherche ----------

async function renderSearch(page) {
  const q = searchQuery();
  const status = h("p", { class: "muted" }, t("searching"));
  page.append(h("h2", { class: "section" }, t("search_results", { q })), status);
  try {
    const { items } = await api("GET", `/api/v1/search/semantic?q=${encodeURIComponent(q)}&limit=120`);
    if (!items.length) { status.textContent = t("no_results"); return; }
    status.remove();
    page.append(h("div", { class: "grid" }, items.map((it, i) => thumb(it, () => openViewer(items, i)))));
  } catch (e) {
    status.textContent = e.status === 503 ? t("search_starting") : e.message;
  }
}

// ---------- Visionneuse ----------

function openViewer(items, index) {
  let i = index, infoOpen = false;
  const root = h("div", { class: "lightbox", role: "dialog", "aria-modal": "true", "aria-label": t("viewer") });
  const onKey = (e) => {
    if (e.key === "Escape") close();
    else if (e.key === "ArrowLeft") go(-1);
    else if (e.key === "ArrowRight") go(1);
  };
  function close() { root.remove(); removeEventListener("keydown", onKey); }
  function go(d) { if (items[i + d]) { i += d; draw(); } }

  async function draw() {
    const it = items[i];
    const base = `/api/v1/media/${it.id}`;
    const media = it.kind === "video"
      ? h("video", { src: `${base}/original`, controls: true, autoplay: true })
      : h("img", { src: `${base}/thumb/md`, alt: "" });
    root.replaceChildren(h("div", { class: "stage" },
      media,
      h("div", { class: "bar" },
        h("button", { onclick: close, "aria-label": t("close") }, "✕"),
        h("span", {}, cap(fullFmt.format(new Date(it.taken_at)))),
        h("span", { class: "spacer" }),
        h("a", { href: `${base}/original`, download: "" }, t("download_original")),
        h("button", { onclick: () => { infoOpen = !infoOpen; draw(); } }, infoOpen ? t("info_hide") : t("info"))),
      i > 0 && h("button", { class: "nav prev", onclick: () => go(-1), "aria-label": t("prev") }, "‹"),
      i < items.length - 1 && h("button", { class: "nav next", onclick: () => go(1), "aria-label": t("next") }, "›")));
    if (!infoOpen) return;
    const panel = h("aside", { class: "info" }, h("p", { class: "muted" }, t("loading")));
    root.append(panel);
    try {
      const d = await api("GET", base);
      if (items[i] === it) panel.replaceChildren(...infoContent(d));
    } catch (e) { panel.replaceChildren(h("p", { class: "error" }, e.message)); }
  }

  addEventListener("keydown", onKey);
  document.body.append(root);
  draw();
}

function infoContent(d) {
  const row = (label, value) => value && [h("div", {}, h("dt", {}, label), h("dd", {}, value))];
  const camera = [d.camera_make, d.camera_model].filter(Boolean).join(" ");
  const size = d.size_bytes < 1048576 ? t("size_kb", { v: Math.round(d.size_bytes / 1024) }) : t("size_mb", { v: (d.size_bytes / 1048576).toFixed(1) });
  return [
    h("h3", {}, cap(fullFmt.format(new Date(d.taken_at)))),
    d.description && h("p", {}, d.description),
    h("dl", {},
      row(t("info_place"), d.city && [d.city, d.admin1, d.country_code].filter(Boolean).join(", ")),
      row(t("info_coords"), d.latitude != null && `${d.latitude.toFixed(5)}, ${d.longitude.toFixed(5)}`),
      row(t("info_camera"), camera),
      row(t("info_file"), d.original_name),
      row(t("info_dims"), d.width && `${d.width} × ${d.height} • ${size}`),
      row(t("info_ocr"), d.ocr_text)),
    d.people.length > 0 && [h("dt", {}, t("info_people")), h("div", { class: "chips" }, d.people.map((p) => h("span", { class: "chip" }, p.name || t("unnamed"))))],
    d.tags.length > 0 && [h("dt", {}, t("info_tags")), h("div", { class: "chips" }, d.tags.map((tag) => h("span", { class: "chip" }, tagLabel(tag))))],
  ].flat().filter(Boolean);
}

// ---------- Import ----------

let computerCardEl; // conservé entre les pages : les envois continuent si on change d'onglet

function renderImport(page) {
  computerCardEl ??= computerCard();
  page.append(
    h("h2", { class: "section" }, t("import_title")),
    h("p", { class: "muted" }, t("import_intro")),
    h("div", { class: "cards" }, computerCardEl, nasCard()));
}

const EXT_MIME = { heic: "image/heic", heif: "image/heif", dng: "image/x-adobe-dng", mkv: "video/x-matroska", "3gp": "video/3gpp", mov: "video/quicktime" };

function mimeOf(file) {
  if (/^(image|video)\//.test(file.type) && !file.type.includes("svg")) return file.type;
  return EXT_MIME[file.name.split(".").pop().toLowerCase()] || null;
}

/** Date du fichier en heure locale murale encodée en UTC, comme les EXIF côté serveur (repli si pas d'EXIF). */
function wallClockIso(ms) {
  const d = new Date(ms), p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}Z`;
}

/** Même protocole que l'app Android (docs/API.md) : hash, init, blocs avec Content-Range, complete. */
async function uploadFile(file, progress) {
  const sha256 = await hashFile(file, (p) => progress("hash", p));
  const init = await api("POST", "/api/v1/uploads", {
    sha256, size: file.size, filename: file.name, mime_type: mimeOf(file), taken_at: wallClockIso(file.lastModified),
  });
  if (init.status === "exists") return "exists";
  const step = Math.min(8 << 20, init.chunk_max || 8 << 20);
  let offset = init.offset, retries = 0;
  while (offset < file.size) {
    const end = Math.min(offset + step, file.size);
    let r;
    try {
      r = await fetch(`/api/v1/uploads/${init.upload_id}`, {
        method: "PUT",
        headers: { Authorization: `Bearer ${token}`, "Content-Range": `bytes ${offset}-${end - 1}/${file.size}` },
        body: file.slice(offset, end),
      });
    } catch (e) { // coupure réseau : on redemande l'offset au serveur et on reprend
      if (++retries > 5) throw e;
      await sleep(2000 * retries);
      offset = (await api("GET", `/api/v1/uploads/${init.upload_id}`)).offset;
      continue;
    }
    if (r.status === 409) {
      if (++retries > 5) throw new Error(t("upload_conflict"));
      const detail = (await r.json()).detail;
      if (detail && typeof detail === "object") offset = detail.offset;
      else await sleep(1500);
      continue;
    }
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    offset = (await r.json()).offset;
    retries = 0;
    progress("upload", offset / file.size);
  }
  const done = await api("POST", `/api/v1/uploads/${init.upload_id}/complete?priority=true`);
  return done.status === "exists" ? "exists" : "uploaded";
}

/** Fichiers d'un glisser-déposer, dossiers parcourus récursivement. */
function droppedFiles(dt) {
  // webkitGetAsEntry doit être appelé avant tout await : la liste est vidée après l'événement
  const entries = [...dt.items].map((i) => i.webkitGetAsEntry?.()).filter(Boolean);
  const out = [];
  async function walk(entry) {
    if (entry.isFile) out.push(await new Promise((res, rej) => entry.file(res, rej)));
    else if (entry.isDirectory) {
      const reader = entry.createReader();
      for (;;) {
        const batch = await new Promise((res, rej) => reader.readEntries(res, rej));
        if (!batch.length) break;
        for (const e of batch) await walk(e);
      }
    }
  }
  return (async () => { for (const e of entries) await walk(e); return out; })();
}

function computerCard() {
  const queueEl = h("div", { class: "queue", "aria-live": "polite" });
  const summary = h("p", { class: "muted" });
  const pending = [];
  const stats = { total: 0, uploaded: 0, exists: 0, failed: 0, ignored: 0 };
  let active = 0;

  function updateSummary() {
    const finished = stats.uploaded + stats.exists + stats.failed;
    summary.textContent = stats.total || stats.ignored
      ? t("summary", { done: finished, total: stats.total, up: stats.uploaded, ex: stats.exists, fail: stats.failed })
        + (stats.ignored ? t("ignored", { n: stats.ignored }) : "")
      : "";
  }

  function row(file) {
    const status = h("span", { class: "muted" }, t("q_pending"));
    const fill = h("div", { class: "bar-fill" });
    const el = h("div", { class: "qitem" }, h("span", { class: "name", title: file.name }, file.name), status, h("div", { class: "bar-track" }, fill));
    return {
      el,
      set(phase, p) {
        status.textContent = t(phase === "hash" ? "q_hash" : "q_upload", { p: Math.round(p * 100) });
        fill.style.width = `${(phase === "hash" ? p * 0.25 : 0.25 + p * 0.75) * 100}%`;
      },
      finish(text, failed) {
        status.textContent = text;
        status.className = failed ? "error" : "muted";
        fill.style.width = "100%";
      },
    };
  }

  function pump() {
    while (active < 2 && pending.length) {
      const job = pending.shift();
      active++;
      uploadFile(job.file, job.row.set)
        .then((r) => { stats[r]++; job.row.finish(r === "exists" ? t("q_exists") : t("q_sent")); },
          (e) => { stats.failed++; job.row.finish(t("q_failed", { msg: e.message }), true); })
        .finally(() => { active--; updateSummary(); pump(); });
    }
    updateSummary();
  }

  function enqueue(files) {
    for (const file of files) {
      if (!mimeOf(file) || file.size === 0) { stats.ignored++; continue; }
      stats.total++;
      const r = row(file);
      queueEl.append(r.el);
      pending.push({ file, row: r });
    }
    pump();
  }

  addEventListener("beforeunload", (e) => { if (active || pending.length) e.preventDefault(); });

  const fileInput = h("input", { type: "file", multiple: true, hidden: true, accept: "image/*,video/*,.heic,.heif,.dng", onchange: (e) => { enqueue([...e.target.files]); e.target.value = ""; } });
  const dirInput = h("input", { type: "file", multiple: true, hidden: true, webkitdirectory: true, onchange: (e) => { enqueue([...e.target.files]); e.target.value = ""; } });
  const drop = h("div", {
    class: "drop",
    ondragover: (e) => { e.preventDefault(); drop.classList.add("over"); },
    ondragleave: () => drop.classList.remove("over"),
    ondrop: async (e) => { e.preventDefault(); drop.classList.remove("over"); enqueue(await droppedFiles(e.dataTransfer)); },
  }, t("drop"));

  const card = h("section", { class: "card" },
    h("h3", {}, t("from_computer")),
    h("p", { class: "muted" }, t("computer_intro")),
    drop,
    h("div", { class: "row-actions" },
      h("button", { class: "btn", onclick: () => fileInput.click() }, t("choose_files")),
      h("button", { class: "btn", onclick: () => dirInput.click() }, t("choose_folder"))),
    fileInput, dirInput, summary, queueEl);
  card.busy = () => active > 0 || pending.length > 0;
  return card;
}

const jobStatus = (s) => (`job_${s}` in STR.fr ? t(`job_${s}`) : s);

function nasCard() {
  let path = "";
  const crumbs = h("nav", { class: "crumbs", "aria-label": t("location") });
  const list = h("ul", { class: "dirs" });
  const info = h("p", { class: "muted" });
  const startBtn = h("button", { class: "btn primary", onclick: start }, t("import_folder"));
  const jobsBody = h("tbody");

  async function browse(p) {
    try { draw(await api("GET", `/api/v1/import/browse?path=${encodeURIComponent(p)}`)); } catch (e) { info.textContent = e.message; }
  }

  function draw(r) {
    path = r.path;
    const parts = r.path ? r.path.split("/") : [];
    crumbs.replaceChildren(h("button", { onclick: () => browse("") }, "NAS"),
      ...parts.flatMap((p, i) => ["/", h("button", { onclick: () => browse(parts.slice(0, i + 1).join("/")) }, p)]));
    list.replaceChildren(...(r.dirs.length
      ? r.dirs.map((d) => h("li", {}, h("button", { onclick: () => browse(r.path ? `${r.path}/${d}` : d) }, "📁 ", d)))
      : [h("li", {}, h("span", { class: "muted", style: "display:block;padding:10px 14px" }, t("no_subfolders")))]));
    info.textContent = t("media_in_folder", { n: r.media_files });
    startBtn.textContent = r.path ? t("import_named", { name: parts.at(-1) }) : t("import_all");
  }

  async function start() {
    if (!confirm(t("confirm_import", { path: path || t("whole_nas") }))) return;
    try { await api("POST", "/api/v1/import/jobs", { path }); toast(t("import_started")); loadJobs(); } catch (e) { toast(e.message); }
  }

  async function loadJobs() {
    try {
      const { jobs } = await api("GET", "/api/v1/import/jobs");
      jobsBody.replaceChildren(...(jobs.length ? jobs.map((j) => h("tr", {},
        h("td", {}, j.path || "NAS"),
        h("td", { title: j.error || "" }, jobStatus(j.status)),
        h("td", {}, j.found), h("td", {}, j.imported), h("td", {}, j.skipped), h("td", {}, j.failed)))
        : [h("tr", {}, h("td", { colspan: 6, class: "muted" }, t("no_imports")))]));
    } catch { /* affiché au prochain rafraîchissement */ }
  }

  const timer = setInterval(() => (document.body.contains(jobsBody) ? loadJobs() : clearInterval(timer)), 4000);
  browse("");
  loadJobs();

  return h("section", { class: "card" },
    h("h3", {}, t("from_nas")),
    h("p", { class: "muted" }, t("nas_intro")),
    crumbs, list, info, startBtn,
    h("h4", {}, t("imports")),
    h("table", { class: "jobs" },
      h("thead", {}, h("tr", {}, ["th_folder", "th_status", "th_found", "th_imported", "th_skipped", "th_failed"].map((k) => h("th", {}, t(k))))),
      jobsBody));
}

if (token) setToken(token); // recrée le cookie s'il a expiré avant le jeton
render();
