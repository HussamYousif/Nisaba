#!/usr/bin/env python3
"""S4 spike: can deepseek-flash read handwriting-ish images, answer briefly, stay silent on notes? TTFT + accuracy."""
import base64, io, json, os, random, sys, time, difflib, urllib.request
from PIL import Image, ImageDraw, ImageFont
KEY = next(l.split("=",1)[1].strip().strip('"') for l in open(".env") if l.startswith("DEEPSEEK_API_KEY"))
FONTS = {"bradley": ("/System/Library/Fonts/Supplemental/Bradley Hand Bold.ttf", 0), "chalk": ("/System/Library/Fonts/Supplemental/Chalkboard.ttc", 0)}
SYSTEM = """You are Nisaba, the spirit of this notebook. The person writes by hand; you see the page as images and answer in ink, beneath their writing.

Decide first: is the NEW writing (the second image, the focus) addressed to you: a question, a request, a "?" or anything that expects a reply? If it is just notes, a list, a drawing, or a crossed-out mistake, reply exactly <silent/> followed by <heard>.

When you answer:
- Be brief and exact; it will be handwritten. Default <= 60 words unless asked for more.
- Answer in the language they wrote in.
- Markup only: paragraphs, - lists, **bold**, $math$, pipe tables. No links, no code, no emoji.
- If the writing is illegible, say so in one line and ask them to rewrite it.

Reply format, exactly one of:
<silent/><heard>verbatim transcription of the new writing</heard>
or
<answer>
...markdown subset...
</answer><heard>verbatim transcription of the new writing</heard>"""
CASES = [
 ("question", "bradley", "What is the capital of Norway?", True),
 ("math", "bradley", "Solve x^2 - 5x + 6 = 0", True),
 ("norwegian", "bradley", "Hva er forskjellen på sol og måne? Æ Ø Å", True),
 ("notes", "bradley", "Buy milk, eggs, call Ola tomorrow", False),
 ("chalk-q", "chalk", "Explain why the sky is blue in two lines", True),
 ("list", "chalk", "- ideas for weekend\n- hiking\n- cinema", False),
]
def render(text, fontkey, size=64, rot=-1.5):
    W, H = 1860, 2480
    page = Image.new("L", (W, H), 246)
    d = ImageDraw.Draw(page); f = ImageFont.truetype(FONTS[fontkey][0], size, index=FONTS[fontkey][1])
    lines = text.split("\n"); y = 300
    boxes = []
    for ln in lines:
        d.text((160, y), ln, font=f, fill=25); boxes.append(d.textbbox((160, y), ln, font=f)); y += int(size*1.5)
    page = page.rotate(rot, fillcolor=246)
    x0 = min(b[0] for b in boxes); y0 = min(b[1] for b in boxes); x1 = max(b[2] for b in boxes); y1 = max(b[3] for b in boxes)
    pad = int(0.1*max(x1-x0, y1-y0)); focus = page.crop((max(0,x0-pad), max(0,y0-pad), min(W,x1+pad), min(H,y1+pad)))
    return page, focus
def jpeg(img, long_edge):
    s = long_edge / max(img.size); 
    if s < 1: img = img.resize((int(img.width*s), int(img.height*s)), Image.LANCZOS)
    b = io.BytesIO(); img.convert("L").save(b, "JPEG", quality=85); return "data:image/jpeg;base64," + base64.b64encode(b.getvalue()).decode(), len(b.getvalue())
def ask(page_uri, focus_uri, detail, model="deepseek-flash", thinking=False, instruction=None, temperature=0.6):
    instruction = instruction or "First image: the whole page. Second image: the new writing (focus). Answer only the new writing."
    body = {"model": model, "stream": True, "max_tokens": 600, "temperature": temperature, "stream_options": {"include_usage": True},
            "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": [
                {"type": "text", "text": instruction},
                {"type": "image_url", "image_url": {"url": page_uri, "detail": detail}},
                {"type": "image_url", "image_url": {"url": focus_uri, "detail": "high"}}]}]}
    if not thinking: body["thinking"] = {"type": "disabled"}
    req = urllib.request.Request("https://api.deepseek.com/chat/completions", json.dumps(body).encode(), {"Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
    t0 = time.time(); ttft = None; out = ""; usage = None
    with urllib.request.urlopen(req, timeout=120) as r:
        for raw in r:
            l = raw.decode().strip()
            if not l.startswith("data:") or l.endswith("[DONE]"): continue
            j = json.loads(l[5:])
            if j.get("usage"): usage = j["usage"]
            for c in j.get("choices", []):
                t = c.get("delta", {}).get("content")
                if t:
                    if ttft is None: ttft = time.time() - t0
                    out += t
    return out, ttft, time.time() - t0, usage
if __name__ == "__main__":
    long_edge = int(sys.argv[1]) if len(sys.argv) > 1 else 1600; detail = sys.argv[2] if len(sys.argv) > 2 else "auto"
    print(f"== long_edge={long_edge} detail={detail}")
    ok_silent = 0; n_silent = 0; accs = []; ttfts = []
    for name, fk, text, expects in CASES:
        page, focus = render(text, fk)
        if "--save" in sys.argv: page.save(f"tools/fixtures/vision/{name}.png"); focus.save(f"tools/fixtures/vision/{name}-focus.png")
        pu, pb = jpeg(page, long_edge); fu, fb = jpeg(focus, 1600)
        try: out, ttft, tot, usage = ask(pu, fu, detail)
        except Exception as e: print(name, "ERROR", e); continue
        silent = "<silent/>" in out[:40]
        heard = out.split("<heard>")[-1].split("</heard>")[0].strip() if "<heard>" in out else ""
        acc = difflib.SequenceMatcher(None, heard.lower().replace("\n"," "), text.lower().replace("\n"," ")).ratio()
        accs.append(acc); ttfts.append(ttft or 0)
        if not expects: n_silent += 1; ok_silent += silent
        print(f"[{name}] silent={silent} (expected {'no' if expects else 'yes'}) ttft={ttft:.2f}s total={tot:.2f}s img={pb//1024}+{fb//1024}KB acc={acc:.2f} tokens={usage and (usage.get('prompt_tokens'), usage.get('completion_tokens'))}")
        print("   heard:", repr(heard)); print("   out:", repr(out[:220]))
    print(f"SUMMARY silent {ok_silent}/{n_silent}  mean acc {sum(accs)/len(accs):.2f}  ttft p50 {sorted(ttfts)[len(ttfts)//2]:.2f}s max {max(ttfts):.2f}s")
