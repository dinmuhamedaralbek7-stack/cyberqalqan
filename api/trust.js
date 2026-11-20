// CyberQalqan — домен сенім деңгейі (Vercel serverless, Node 18+)
// GET /api/trust?d=example.kz
// Қауіпсіздік: тек домен атауы қабылданады; ішкі/жеке IP-ге қосылмайды (SSRF қорғанысы);
// сайттың мазмұны ЖҮКТЕЛМЕЙДІ — тек DNS, TLS-сертификат және ашық анықтамалықтар.
"use strict";
const dns = require("dns").promises;
const net = require("net");
const tls = require("tls");

const UA = "CyberQalqan-TrustBot/1.1 (+anti-phishing)";
const DOMAIN_RE = /^(?=.{3,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;
const TWO_LEVEL = new Set(["com.kz", "gov.kz", "org.kz", "edu.kz", "net.kz", "co.uk", "com.ru", "com.ua", "org.ru", "com.tr", "co.jp"]);
const WHOIS = { kz: "whois.nic.kz", ru: "whois.tcinet.ru", su: "whois.tcinet.ru", "рф": "whois.tcinet.ru", uz: "whois.cctld.uz",
  by: "whois.cctld.by", ua: "whois.ua", kg: "whois.kg", com: "whois.verisign-grs.com", net: "whois.verisign-grs.com", org: "whois.pir.org" };
const COUNTRY_KK = { KZ: "Қазақстан", RU: "Ресей", US: "АҚШ", DE: "Германия", NL: "Нидерланды", FR: "Франция", GB: "Ұлыбритания",
  FI: "Финляндия", TR: "Түркия", CN: "Қытай", HK: "Гонконг", SG: "Сингапур", UA: "Украина", UZ: "Өзбекстан", KG: "Қырғызстан",
  BY: "Беларусь", LT: "Литва", LV: "Латвия", EE: "Эстония", PL: "Польша", SE: "Швеция", CA: "Канада", JP: "Жапония", IN: "Үндістан",
  AE: "БАӘ", SC: "Сейшел аралдары", PA: "Панама", BG: "Болгария", RO: "Румыния", MD: "Молдова", CZ: "Чехия", IE: "Ирландия" };

// ---------- көмекші ----------
const sleep = ms => new Promise(r => setTimeout(r, ms));
function regDomain(host) {
  const p = host.split(".");
  if (p.length <= 2) return host;
  const last2 = p.slice(-2).join(".");
  return TWO_LEVEL.has(last2) ? p.slice(-3).join(".") : last2;
}
async function getJSON(url, ms = 4500, init = {}) {
  const c = new AbortController(); const t = setTimeout(() => c.abort(), ms);
  try {
    const r = await fetch(url, { ...init, signal: c.signal, headers: { "user-agent": UA, accept: "application/json", ...(init.headers || {}) } });
    if (!r.ok) return null;
    const txt = await r.text();
    if (txt.length > 2_000_000) return null;
    return JSON.parse(txt);
  } catch { return null; } finally { clearTimeout(t); }
}
async function getText(url, ms = 5000) {
  const c = new AbortController(); const t = setTimeout(() => c.abort(), ms);
  try { const r = await fetch(url, { signal: c.signal, headers: { "user-agent": UA } }); return r.ok ? await r.text() : null; }
  catch { return null; } finally { clearTimeout(t); }
}
function isPrivateIP(ip) {
  if (net.isIPv4(ip)) {
    const [a, b] = ip.split(".").map(Number);
    return a === 10 || a === 127 || a === 0 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127) || a >= 224;
  }
  const v = ip.toLowerCase();
  return v === "::1" || v === "::" || v.startsWith("fc") || v.startsWith("fd") || v.startsWith("fe80") || v.startsWith("::ffff:");
}
function parseDate(s) {
  if (!s) return null;
  const clean = String(s).replace(/\(.*?\)/g, "").replace(/\s+UTC$/i, "Z").trim();
  const d = Date.parse(clean) || Date.parse(clean.replace(" ", "T") + (/[zZ+]/.test(clean) ? "" : "Z"));
  return Number.isFinite(d) && d > 0 ? new Date(d) : null;
}

// ---------- 1) Домен жасы: RDAP, болмаса WHOIS ----------
async function rdapInfo(reg) {
  const j = await getJSON(`https://rdap.org/domain/${encodeURIComponent(reg)}`, 5000);
  if (!j || !Array.isArray(j.events)) return null;
  const ev = j.events.find(e => /registration/i.test(e.eventAction || ""));
  let registrar = null;
  for (const ent of j.entities || []) {
    if ((ent.roles || []).includes("registrar")) {
      const fn = (ent.vcardArray && ent.vcardArray[1] || []).find(x => x[0] === "fn");
      registrar = fn ? String(fn[3]).slice(0, 80) : null;
    }
  }
  return ev ? { created: parseDate(ev.eventDate), registrar, source: "RDAP" } : null;
}
function whoisQuery(server, query, ms = 5000) {
  return new Promise(resolve => {
    let data = ""; let done = false;
    const end = () => { if (!done) { done = true; try { sock.destroy(); } catch {} resolve(data || null); } };
    const sock = net.connect({ host: server, port: 43, timeout: ms }, () => sock.write(query + "\r\n"));
    sock.setEncoding("utf8");
    sock.on("data", d => { data += d; if (data.length > 65536) end(); });
    sock.on("end", end); sock.on("error", end); sock.on("timeout", end);
    setTimeout(end, ms + 500);
  });
}
async function whoisInfo(reg) {
  const tld = reg.split(".").pop();
  const server = WHOIS[tld];
  if (!server) return null;
  const txt = await whoisQuery(server, reg);
  if (!txt) return null;
  const m = txt.match(/(?:Domain created|Creation Date|created|Registered on|Registration Time|Registration Date)\s*:\s*([^\r\n]+)/i);
  const r = txt.match(/(?:Registrar|Current Registar|registrar)\s*(?:Name)?\s*:\s*([^\r\n]+)/i);
  const created = m ? parseDate(m[1]) : null;
  return created ? { created, registrar: r ? r[1].trim().slice(0, 80) : null, source: "WHOIS" } : null;
}

// ---------- 2) TLS сертификат ----------
function tlsInfo(host, ip) {
  return new Promise(resolve => {
    let done = false;
    const fin = v => { if (!done) { done = true; try { s.destroy(); } catch {} resolve(v); } };
    const s = tls.connect({ host: ip, port: 443, servername: host, rejectUnauthorized: false, timeout: 5000 }, () => {
      const c = s.getPeerCertificate();
      fin({ authorized: s.authorized, error: s.authorizationError ? String(s.authorizationError) : null,
        issuer: c && c.issuer ? (c.issuer.O || c.issuer.CN || "").slice(0, 60) : null,
        validFrom: c && c.valid_from ? new Date(c.valid_from) : null, validTo: c && c.valid_to ? new Date(c.valid_to) : null });
    });
    s.on("error", () => fin(null)); s.on("timeout", () => fin(null));
    setTimeout(() => fin(null), 6000);
  });
}

// ---------- 3) Фишинг базалары ----------
let openphishCache = { at: 0, hosts: null };
async function openphishHosts() {
  if (openphishCache.hosts && Date.now() - openphishCache.at < 60 * 60 * 1000) return openphishCache.hosts;
  const txt = await getText("https://openphish.com/feed.txt", 6000);
  if (!txt) return openphishCache.hosts;
  const set = new Set();
  for (const line of txt.split(/\r?\n/)) { try { set.add(new URL(line.trim()).hostname.toLowerCase()); } catch {} }
  openphishCache = { at: Date.now(), hosts: set };
  return set;
}
async function safeBrowsing(endpoint, key, host) {
  if (!key) return undefined;
  const body = { client: { clientId: "cyberqalqan", clientVersion: "1.1" }, threatInfo: {
    threatTypes: ["MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE", "POTENTIALLY_HARMFUL_APPLICATION"],
    platformTypes: ["ANY_PLATFORM"], threatEntryTypes: ["URL"],
    threatEntries: [{ url: `http://${host}/` }, { url: `https://${host}/` }] } };
  const j = await getJSON(`${endpoint}?key=${encodeURIComponent(key)}`, 5000,
    { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
  if (j === null) return undefined;
  return !(j.matches && j.matches.length);
}

// ---------- жылдамдықты шектеу (бір сервер данасы ішінде) ----------
const hits = new Map();
function limited(ip) {
  const now = Date.now(); const w = hits.get(ip) || [];
  const recent = w.filter(t => now - t < 60_000); recent.push(now); hits.set(ip, recent);
  if (hits.size > 5000) hits.clear();
  return recent.length > 30;
}

// ---------- негізгі ----------
module.exports = async (req, res) => {
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.setHeader("X-Content-Type-Options", "nosniff");
  if (req.method !== "GET") { res.statusCode = 405; return res.end('{"error":"method"}'); }
  const ipAddr = String(req.headers["x-forwarded-for"] || "").split(",")[0].trim() || "x";
  if (limited(ipAddr)) { res.statusCode = 429; return res.end('{"error":"rate_limited"}'); }

  let host = String((req.query && req.query.d) || "").trim().toLowerCase().replace(/\.$/, "");
  if (host.startsWith("www.")) host = host.slice(4);
  if (!DOMAIN_RE.test(host)) { res.statusCode = 400; return res.end('{"error":"bad_domain"}'); }
  const reg = regDomain(host);

  const items = []; const facts = { domain: host, registered: reg };
  let score = 50;
  const add = (lvl, text, pts) => { items.push({ lvl, text, pts }); score += pts; };

  // DNS (SSRF қорғанысы)
  let ips = [];
  try { ips = (await dns.lookup(host, { all: true })).map(a => a.address); } catch {}
  const publicIps = ips.filter(ip => !isPrivateIP(ip));
  if (ips.length && !publicIps.length) { res.statusCode = 400; return res.end('{"error":"private_address"}'); }

  const [rdap, tranco, op, gsb, ysb, geo, tlsRes] = await Promise.all([
    rdapInfo(reg).then(r => r || whoisInfo(reg)).catch(() => null),
    getJSON(`https://tranco-list.eu/api/ranks/domain/${encodeURIComponent(reg)}`, 4500),
    openphishHosts().catch(() => null),
    safeBrowsing("https://safebrowsing.googleapis.com/v4/threatMatches:find", process.env.GOOGLE_SAFE_BROWSING_KEY, host),
    safeBrowsing("https://sba.yandex.net/v4/threatMatches:find", process.env.YANDEX_SAFE_BROWSING_KEY, host),
    publicIps[0] ? getJSON(`https://ipwho.is/${publicIps[0]}?fields=success,country,country_code,flag,connection`, 4000) : null,
    publicIps[0] ? tlsInfo(host, publicIps[0]) : null,
  ]);

  // 1) Жасы
  if (rdap && rdap.created) {
    const days = Math.floor((Date.now() - rdap.created.getTime()) / 86400000);
    const years = Math.floor(days / 365.25);
    facts.created = rdap.created.toISOString().slice(0, 10); facts.ageDays = days; facts.registrar = rdap.registrar;
    if (days < 7) add("bad", `Домен небәрі ${days} күн бұрын тіркелген — алаяқтық сайттарға өте тән`, -35);
    else if (days < 30) add("bad", `Домен ${days} күн бұрын тіркелген — өте жаңа, сенімсіз`, -25);
    else if (days < 180) add("warn", `Доменге ${Math.round(days / 30)} ай — жаңа сайт`, -8);
    else if (years < 1) add("warn", `Доменге 1 жылға жетпейді`, 0);
    else if (years < 3) add("good", `Доменге ${years} жыл`, 5);
    else if (years < 10) add("good", `Доменге ${years} жыл — біршама ерте тіркелген`, 12);
    else add("good", `Доменге ${years} жыл — ерте тіркелген, беделі бар`, 20);
  } else {
    add("neutral", "Домен жасын анықтау мүмкін болмады", 0);
  }

  // 2) Танымалдылық (Tranco)
  const rank = tranco && Array.isArray(tranco.ranks) && tranco.ranks.length
    ? tranco.ranks.slice().sort((a, b) => String(b.date).localeCompare(String(a.date)))[0].rank : null;
  facts.rank = rank || null;
  if (rank) {
    const r = Number(rank).toLocaleString("ru-RU");
    if (rank <= 10000) add("good", `Сайт әлемдік рейтингте ${r}-орында — өте танымал`, 15);
    else if (rank <= 100000) add("good", `Сайт әлемдік рейтингте ${r}-орында`, 10);
    else add("good", `Сайт әлемдік рейтингте ${r}-орында`, 7);
  } else if (tranco) {
    add("neutral", "Әлемдік рейтингтің үздік 1 млн сайты қатарында жоқ", 0);
  } else {
    add("neutral", "Рейтинг деректері қолжетімсіз", 0);
  }

  // 3) DNS / хостинг
  if (!ips.length) add("bad", "Домен ешбір серверге бағытталмаған (DNS жазбасы жоқ)", -10);
  if (geo && geo.success) {
    const cc = geo.country_code || ""; const flag = geo.flag && geo.flag.emoji ? geo.flag.emoji + " " : "";
    const org = geo.connection && (geo.connection.org || geo.connection.isp) || "";
    facts.country = cc; facts.hosting = org;
    add("neutral", `Сервер орналасқан жер: ${flag}${COUNTRY_KK[cc] || geo.country || cc}${org ? " · " + String(org).slice(0, 50) : ""}`, 0);
  }

  // 4) HTTPS
  if (tlsRes) {
    facts.https = tlsRes.authorized; facts.certIssuer = tlsRes.issuer;
    const certAge = tlsRes.validFrom ? Math.floor((Date.now() - tlsRes.validFrom.getTime()) / 86400000) : null;
    if (tlsRes.authorized) {
      add("good", `Қорғалған байланыс (HTTPS)${tlsRes.issuer ? " · сертификат: " + tlsRes.issuer : ""}`, 7);
      if (certAge !== null && certAge < 3 && facts.ageDays !== undefined && facts.ageDays < 60)
        add("warn", `Сертификат ${certAge} күн бұрын ғана берілген — жаңа сайтпен бірге күдікті`, -5);
    } else {
      add("bad", "HTTPS сертификаты жарамсыз немесе жалған", -12);
    }
  } else if (publicIps.length) {
    add("warn", "Қорғалған байланыс (HTTPS) жоқ", -10);
  }

  // 5) Фишинг базалары
  if (op) {
    const listed = op.has(host) || op.has(reg);
    facts.openphish = listed;
    if (listed) add("bad", "OpenPhish фишинг базасында ТІРКЕЛГЕН!", -60);
    else add("good", "OpenPhish фишинг базасында жоқ", 0);
  }
  if (gsb !== undefined) gsb ? add("good", "Google деректері бойынша қауіпсіз", 0) : add("bad", "Google қауіпті сайт деп белгілеген!", -60);
  if (ysb !== undefined) ysb ? add("good", "Яндекс деректері бойынша қауіпсіз", 0) : add("bad", "Яндекс қауіпті сайт деп белгілеген!", -60);

  facts.listed = facts.openphish === true || gsb === false || ysb === false;
  score = Math.max(0, Math.min(100, Math.round(score)));
  res.setHeader("Cache-Control", "public, s-maxage=3600, max-age=600");
  res.statusCode = 200;
  res.end(JSON.stringify({ ok: true, domain: host, score, items, facts, checkedAt: new Date().toISOString() }));
};
