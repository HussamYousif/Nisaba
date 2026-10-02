#!/usr/bin/env python3
"""Does the system prompt (read from the Kotlin source) answer everything (statements included) and stay silent only on nothing?
   usage: prompt_gate.py [--runs N]"""
import re, sys, importlib.util
spec = importlib.util.spec_from_file_location("sv", "scripts/spike_vision.py"); sv = importlib.util.module_from_spec(spec); spec.loader.exec_module(sv)
src = open("domain/src/main/kotlin/com/nisaba/domain/conversation/ConversationPromptRules.kt").read()
prompt = re.search(r'val SYSTEM_PROMPT: String = """(.*?)""".trim\(\)', src, re.S).group(1).strip().replace("${'$'}", "$")
sv.SYSTEM = prompt
NOTES = []  # v3: nothing readable is silent
STATEMENTS = ["test", "hello", "What", "hullo", "Buy milk, eggs, call Ola tomorrow", "Meeting 3pm room 4", "TODO: send invoice",
         "- ideas\n- hiking\n- cinema", "note to self: pay rent", "2 + 2 = 4", "Chapter 3 summary", "Oslo",
         "What test", "hmm", "ok thanks", "1. a\n2. b", "maybe later", "fjords",
              "Ingrid is my girlfriend and she is amazing", "Hussam has a big dodo"]
QUESTIONS = STATEMENTS + ["What is the capital of Norway?", "Solve x^2 - 5x + 6 = 0", "Explain gravity in one line", "How tall is Mount Everest?",
             "Translate good morning to Norwegian", "tell me a joke", "Why is the sky blue", "Hva er 12 ganger 12?"]
runs = int(sys.argv[sys.argv.index("--runs") + 1]) if "--runs" in sys.argv else 1
def check(text, fk, want_silent):
    page, focus = sv.render(text, fk)
    pu, _ = sv.jpeg(page, 1600); fu, _ = sv.jpeg(focus, 1600)
    out, ttft, tot, _ = sv.ask(pu, fu, "auto")
    silent = "<silent/>" in out[:40]
    return silent, out
bad = []; n_ok = 0; n = 0
for text in NOTES + QUESTIONS:
    want_silent = text in NOTES
    for r in range(runs):
        fk = "bradley" if r % 2 == 0 else "chalk"
        try: silent, out = check(text, fk, want_silent)
        except Exception as e: print("ERR", text, e); continue
        n += 1; ok = silent == want_silent; n_ok += ok
        if not ok: bad.append((text, "silent" if silent else "answered", out.replace("\n", " ")[:110]))
print(f"prompt gate: {n_ok}/{n} correct  (notes should be silent: {len(NOTES)}, questions answered: {len(QUESTIONS)}, x{runs})")
for t, got, out in bad: print(f"  WRONG  {t!r:40} -> {got}: {out}")
