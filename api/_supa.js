// CyberQalqan ↔ Supabase (тек сервер жағы). Файл «_» белгісімен басталады — Vercel оны жеке маршрут етпейді.
// Vercel → Settings → Environment Variables:
//   SUPABASE_URL       = https://<project>.supabase.co
//   SUPABASE_ANON_KEY  = publishable (anon) кілт — ол ашық болуға арналған; деректерді RLS + функциялар қорғайды.
// service_role кілтін ЕШҚАШАН мұнда қоймаңыз.
"use strict";
const crypto = require("crypto");
const URL_ = (process.env.SUPABASE_URL || "").replace(/\/+$/, "");
const KEY = process.env.SUPABASE_ANON_KEY || process.env.SUPABASE_PUBLISHABLE_KEY || "";
const enabled = () => /^https:\/\/[a-z0-9-]+\.supabase\.co$/.test(URL_) && KEY.length > 20;

async function rpc(fn, args, ms = 3000) {
  if (!enabled()) return null;
  const c = new AbortController(); const t = setTimeout(() => c.abort(), ms);
  try {
    const r = await fetch(`${URL_}/rest/v1/rpc/${fn}`, {
      method: "POST", signal: c.signal,
      headers: { apikey: KEY, ...(KEY.startsWith("eyJ") ? { authorization: "Bearer " + KEY } : {}), "content-type": "application/json" },
      body: JSON.stringify(args || {})
    });
    if (!r.ok) return null;
    const txt = await r.text();
    return txt ? JSON.parse(txt) : {};
  } catch { return null; } finally { clearTimeout(t); }
}

// Тұздалған, күн сайын ауысатын IP хэші — IP-дің өзі сақталмайды
function clientId(req) {
  const ip = String(req.headers["x-forwarded-for"] || req.socket?.remoteAddress || "?").split(",")[0].trim();
  const day = new Date().toISOString().slice(0, 10);
  return crypto.createHash("sha256").update((process.env.CQ_SALT || "cq-salt-2026") + "|" + day + "|" + ip).digest("hex").slice(0, 32);
}
module.exports = { rpc, enabled, clientId };
