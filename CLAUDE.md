# Nisaba

Ink notebook; write, pause, and an AI answers in handwriting. **This is the `vanilla` branch**: ordinary LCD Android tablets, an APK for friends (DESIGN D19). The Boox Note Air 5C build lives on `fresh-start`; bring its work over with `git merge fresh-start` and keep vanilla's diff small. Read `DESIGN.md` (what/why), `PLAN.md` (how), `PROGRESS.md` (state; update §2 and §9 when you stop).

## Rules
- Dependency rule: `app → infra → domain`, `app → scribe → domain`. `:domain`/`:scribe`/`:tools` are pure JVM.
- **File roles** (DESIGN §3.3): every file ends in a role suffix (Model, Rules, Ports, UseCase, Session, Parser, Client, Store, Dto, Mapper, Renderer, ViewModel, UiState, Screen, Component, View, Device, Registry, Animator, Cache, Database, + Colors/Type/Theme). `./gradlew check` enforces it (`verifyFileRoles`). Entry points exempt: NisabaApp, MainActivity, AppGraph, Navigation, ToolsMain.
- Rules files are pure functions; ports live in domain, adapters in infra.
- Same look as the Boox build (no fades/ripples, paged lists), but frames may be smooth (~60 fps). No Onyx SDK here.
- Never commit unless asked.

## Commands
- `source scripts/env.sh` first (toolchain lives in `.tools/`).
- `./gradlew check assembleDebug` · `scripts/device.sh install|start|log|shot|props`
- APK for friends: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (id `com.nisaba.vanilla`).
- Test tablet: emulator AVD `vanilla_tablet` (Pixel Tablet, 1600×2560 @320dpi, Android 15) in `.tools/avd`; stylus via `adb shell input stylus swipe …`.
- API key comes from `.env` (DEEPSEEK_API_KEY) via BuildConfig.DEV_DEEPSEEK_API_KEY, **embedded in release too** (D19).

## Done
`./gradlew check` green; tests for new logic; verified on device if input/rendering; docs updated.
