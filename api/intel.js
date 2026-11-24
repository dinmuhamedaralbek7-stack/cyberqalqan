// CyberQalqan — қауымдастықтың қауіп базасы (Supabase)
// GET  /api/intel?action=stats                       → жалпы статистика
// GET  /api/intel?action=check&kind=domain&value=x   → бұл домен/APK туралы хабарламалар
// POST /api/intel {action:"report", kind, value, category, score}  → қауіп туралы хабарлау
// POST /api/intel {action:"log", channel, dangerous}             → анонимді санауыш
"use strict";
const { rpc, enabled, clientId } = require("./_supa");
const KINDS = ["domain", "apk_sha256", "apk_package"];
const CATS = ["phishing","fake_bank","sms_stealer","credential_phishing","rat","spyware","overlay","crypto_scam","remote_access","other"];
const RATE = new Map();
function limited(id, max) {
  const now = Date.now(); const a = (RATE.get(id) || []).filter(t => now - t < 60000); a.push(now); RATE.set(id, a);
  if (RATE.size > 5000) RATE.clear(); return a.length > max;
}
async function body(req) {
  if (req.body && typeof req.body === "object") return req.body;
  let raw = ""; for await (const ch of req) { raw += ch; if (raw.length > 4000) throw new Error("big"); }
  return JSON.parse(raw || "{}");
}
const s = (v, n) => String(v == null ? "" : v).slice(0, n);

module.exports = async (req, res) => {
  res.setHeader("X-Content-Type-Options", "nosniff");
  if (!enabled()) { res.setHeader("Cache-Control", "no-store"); return res.status(200).json({ ok: false, enabled: false }); }
  const id = clientId(req);
  if (limited(id, 60)) return res.status(429).json({ ok: false, error: "rate_limited" });
  try {
    if (req.method === "GET") {
      const q = req.query || Object.fromEntries(new URL(req.url, "http://x").searchParams);
      if (q.action === "stats") {
        res.setHeader("Cache-Control", "public, s-maxage=60, stale-while-revalidate=300");
        const r = await rpc("get_stats", {});
        return res.status(r ? 200 : 502).json(r ? { ok: true, enabled: true, ...r } : { ok: false, enabled: true });
      }
      if (q.action === "check") {
        if (!KINDS.includes(q.kind)) return res.status(400).json({ ok: false, error: "kind" });
        res.setHeader("Cache-Control", "public, s-maxage=120");
        const r = await rpc("check_threat", { p_kind: q.kind, p_value: s(q.value, 300) });
        return res.status(r ? 200 : 502).json(r || { ok: false });
      }
      return res.status(400).json({ ok: false, error: "action" });
    }
    if (req.method !== "POST") return res.status(405).json({ ok: false });
    res.setHeader("Cache-Control", "no-store");
    const o = req.headers.origin; // браузерден — тек өз сайтымыздан; Android қосымшасы Origin жібермейді
    if (o) { try { if (new URL(o).host !== (req.headers["x-forwarded-host"] || req.headers.host)) return res.status(403).json({ ok: false }); } catch { return res.status(403).json({ ok: false }); } }
    const b = await body(req);
    if (b.action === "report") {
      if (!KINDS.includes(b.kind)) return res.status(400).json({ ok: false, error: "kind" });
      if (limited("r" + id, 20)) return res.status(429).json({ ok: false, error: "rate_limited" });
      const r = await rpc("report_threat", { p_kind: b.kind, p_value: s(b.value, 300), p_category: CATS.includes(b.category) ? b.category : "other",
        p_score: Math.max(0, Math.min(100, parseInt(b.score, 10) || 0)), p_source: b.source === "user" ? "user" : "auto", p_client: id });
      return res.status(r ? 200 : 502).json(r || { ok: false });
    }
    if (b.action === "log") {
      const ch = ["web", "android", "apk", "bubble"].includes(b.channel) ? b.channel : "web";
      await rpc("log_check", { p_channel: ch, p_dangerous: !!b.dangerous });
      return res.status(200).json({ ok: true });
    }
    return res.status(400).json({ ok: false, error: "action" });
  } catch { return res.status(400).json({ ok: false, error: "bad_request" }); }
};
