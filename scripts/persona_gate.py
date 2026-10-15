#!/usr/bin/env python3
"""Smoke test for the voices: every persona answers a few handwritten-ish questions; checks the reply contract and
prints the answers to read. usage: persona_gate.py [--persona ID ...] (default: all). Costs ~5 small requests per voice."""
import re, sys, importlib.util
def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); return m
sv = load("sv", "scripts/spike_vision.py"); ps = load("ps", "scripts/prompt_source.py")

CASES = [  # (writing, a word the answer must contain, or None)
    ("What is the capital of Norway?", "Oslo"),
    ("Solve x^2 - 5x + 6 = 0", "3"),
    ("Hva er 12 ganger 12?", "144"),
    ("I failed my driving test today", None),
    ("Give me a title for a story about a lighthouse keeper", None),
]
NORWEGIAN = re.compile(r"\b(er|og|det|den|et|en|som|til|på|så|ikke|eller|du|jeg|men|med|for|av|bare|enkel|svar)\b", re.I)
EMOJI = re.compile("[\U0001F300-\U0001FAFF☀-➿]")

def check(reply, voice, must):
    problems = []
    body = reply.split("<answer>", 1)[-1].split("</answer>", 1)[0] if "<answer>" in reply else ""
    if not body.strip(): problems.append("no <answer>")
    if "<heard>" not in reply: problems.append("no <heard>")
    words = len(re.sub(r"[#*$|\\-]", " ", body).split())
    if words > voice["words"] * 1.5: problems.append(f"{words} words > 1.5x{voice['words']}")
    if "{words}" in reply: problems.append("template leak")
    if EMOJI.search(body): problems.append("emoji")
    if must and must.lower() not in body.lower(): problems.append(f"missing {must!r}")
    if must == "144" and not NORWEGIAN.search(re.sub(r"\$[^$]*\$", " ", body)): problems.append("not in Norwegian")
    notes = ["*italics* (the parser strips them)"] if re.search(r"(?<!\*)\*(?!\*)", body) else []
    return problems, body.strip(), words, notes

voices = ps.personas()
args = [a for a in sys.argv[1:] if not a.startswith("--")]
only = int(sys.argv[sys.argv.index("--case") + 1]) if "--case" in sys.argv else None  # e.g. --case 2: just the Norwegian one
picked = [a for a in args if a in voices] or list(voices)
bad = 0; n = 0
for pid in picked:
    v = voices[pid]; sv.SYSTEM = ps.system_prompt(v)
    print(f"\n=== {v['name']} ({pid}, <= {v['words']} words, t={v['temperature']})")
    for i, (text, must) in enumerate(CASES):
        if only is not None and i != only: continue
        page, focus = sv.render(text, "bradley" if i % 2 == 0 else "chalk")
        pu, _ = sv.jpeg(page, 1600); fu, _ = sv.jpeg(focus, 1600)
        try: out, ttft, tot, _ = sv.ask(pu, fu, "auto", instruction=ps.instruction(v), temperature=v["temperature"])
        except Exception as e: print("  ERR", text, e); bad += 1; n += 1; continue
        problems, body, words, notes = check(out, v, must); n += 1; bad += bool(problems)
        print(f"  [{'ok ' if not problems else 'BAD'}] {text!r} ({words}w, {ttft or 0:.1f}s) {'; '.join(problems + notes)}")
        print("       " + body.replace("\n", " / ")[:400])
print(f"\npersona gate: {n - bad}/{n} replies kept the contract")
