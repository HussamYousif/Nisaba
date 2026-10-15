"""The system prompt and voice line exactly as the app builds them (ConversationPromptRules.kt + PersonaRegistry.kt),
read from the Kotlin sources so the gates never drift from the app."""
import re

RULES = "domain/src/main/kotlin/com/nisaba/domain/conversation/ConversationPromptRules.kt"
REGISTRY = "domain/src/main/kotlin/com/nisaba/domain/persona/PersonaRegistry.kt"


def _kt(s):
    return s.replace("${'$'}", "$")


def personas():
    """id -> {id, name, words, temperature, voice}, in picker order."""
    src = open(REGISTRY).read()
    pat = r'Persona\(\s*id = "(\w+)", name = "([^"]+)".*?words = (\d+), temperature = ([\d.]+),\s*voice = """(.*?)"""'
    return {m[0]: dict(id=m[0], name=m[1], words=int(m[2]), temperature=float(m[3]), voice=m[4].strip())
            for m in re.findall(pat, src, re.S)}


def system_prompt(p):
    contract = re.search(r'private val CONTRACT: String = """(.*?)""".trim\(\)', open(RULES).read(), re.S).group(1).strip()
    return _kt(p["voice"] + "\n\n" + contract.replace("{words}", str(p["words"])))


def instruction(p):
    """The instruction a fresh question gets (focus image), voice line included."""
    src = open(RULES).read()
    focus = re.search(r'const val INSTRUCTION_WITH_FOCUS = "(.*?)"', src).group(1)
    line = re.search(r'else -> "(Stay in your voice.*?)"', src).group(1) + re.search(r'\} \+ "(.*?)"', src).group(1)
    return focus + " " + line.replace("${persona.name}", p["name"]).replace("${persona.words}", str(p["words"]))
