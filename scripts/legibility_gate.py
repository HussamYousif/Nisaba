#!/usr/bin/env python3
"""M3.9: can the vision model read what the hand writes? Usage: legibility_gate.py image.png [more.png ...]"""
import base64, difflib, json, re, sys, urllib.request
KEY = next(l.split("=", 1)[1].strip().strip('"') for l in open(".env") if l.startswith("DEEPSEEK_API_KEY"))
EXPECTED = ("The sky is blue Sunlight hits air molecules which scatter short blue wavelengths far more than red ones "
            "That scattered light reaches your eyes from every direction Rayleigh scattering Shorter wavelength scatters more "
            "1 Sunset looks red 2 Longer path removes blue Hva er hovedstaden i Norge Oslo på Østlandet")
def words(t): return re.sub(r"[^\wæøåÆØÅ ]", " ", t.lower()).split()
def transcribe(path):
    uri = "data:image/png;base64," + base64.b64encode(open(path, "rb").read()).decode()
    body = {"model": "deepseek-flash", "max_tokens": 600, "thinking": {"type": "disabled"}, "messages": [{"role": "user", "content": [
        {"type": "text", "text": "Transcribe the handwriting exactly, word for word. Output only the text."},
        {"type": "image_url", "image_url": {"url": uri, "detail": "high"}}]}]}
    req = urllib.request.Request("https://api.deepseek.com/chat/completions", json.dumps(body).encode(),
                                 {"Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=120))["choices"][0]["message"]["content"]
exp = words(EXPECTED)
for p in sys.argv[1:]:
    got = words(transcribe(p))
    sm = difflib.SequenceMatcher(None, exp, got)
    print(f"{p}: word accuracy {sm.ratio():.2f}  ({len(got)} words read / {len(exp)} expected)")
    for tag, a0, a1, b0, b1 in sm.get_opcodes():
        if tag != "equal": print("    ", tag, exp[a0:a1], "->", got[b0:b1])
