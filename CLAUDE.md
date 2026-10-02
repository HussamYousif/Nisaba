# Nisaba

Boox Note Air 5C ink notebook; write, pause, and an AI answers in handwriting. Read `DESIGN.md` (what/why), `PLAN.md` (how), `PROGRESS.md` (state; update §2 and §9 when you stop).

## Rules
- Dependency rule: `app → infra → domain`, `app → scribe → domain`. `:domain`/`:scribe`/`:tools` are pure JVM.
- **File roles** (DESIGN §3.3): every file ends in a role suffix (Model, Rules, Ports, UseCase, Session, Parser, Client, Store, Dto, Mapper, Renderer, ViewModel, UiState, Screen, Component, View, Device, Registry, Animator, Cache, Database, + Colors/Type/Theme). `./gradlew check` enforces it (`verifyFileRoles`). Entry points exempt: NisabaApp, MainActivity, AppGraph, Navigation, ToolsMain.
- Rules files are pure functions; ports live in domain, adapters in infra.
- E-ink: no fades/scrolling/ripples; discrete frames only.
- Never commit unless asked.

## Commands
- `source scripts/env.sh` first (toolchain lives in `.tools/`).
- `./gradlew check assembleDebug` · `scripts/device.sh install|start|log|shot|props`
- Device: NoteAir5C, Android 15, 1860×2480 @300dpi, fw 4.2.1.
- Debug API key comes from `.env` (DEEPSEEK_API_KEY) via BuildConfig.DEV_DEEPSEEK_API_KEY.

## Done
`./gradlew check` green; tests for new logic; verified on device if input/rendering; docs updated.
