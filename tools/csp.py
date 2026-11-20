"""web/index.html ішіндегі скрипт өзгерген сайын CSP хешін жаңартады: python3 tools/csp.py"""
import re, hashlib, base64, json, pathlib
root = pathlib.Path(__file__).resolve().parent.parent
p = root / "web/index.html"; s = p.read_text(encoding="utf-8")
body = re.search(r"<script>([\s\S]*?)</script>", s).group(1)
h = base64.b64encode(hashlib.sha256(body.encode("utf-8")).digest()).decode()
s = re.sub(r"'sha256-[A-Za-z0-9+/=]+'", f"'sha256-{h}'", s)
p.write_text(s, encoding="utf-8")
v = root / "vercel.json"; j = json.loads(v.read_text(encoding="utf-8"))
for blk in j.get("headers", []):
    for hd in blk["headers"]:
        if hd["key"] == "Content-Security-Policy":
            hd["value"] = re.sub(r"'sha256-[A-Za-z0-9+/=]+'", f"'sha256-{h}'", hd["value"])
v.write_text(json.dumps(j, indent=2, ensure_ascii=False), encoding="utf-8")
print("CSP sha256:", h)
