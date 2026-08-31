# AGENTS.md — harness settings: how we write code here

These rules bind **every** change to this repository, by any agent or human.
The full ruleset lives in [`docs/architecture.md`](docs/architecture.md); this
file is the enforceable summary.

## 1. Low coupling (the biggest evil)

- A feature lives in **its own folder with its own files** — understandable,
  changeable, deletable in isolation.
- **Modularity beats DRY.** Code that is easy to delete beats code that is
  cleverly shared.
- Cross module/domain boundaries happen **only through public surfaces** —
  never into another module's internals. If you need something internal, the
  owning module is missing a public leaf: add it there.
- Dependency graph stays acyclic. `core` never imports ui/data/client;
  `data` never imports core; leaves (client, facade, raw storage, models)
  call nothing inside their own module.

## 2. Additive, not disruptive

- New features **must not change the behaviour of existing unrelated
  features.** No edits to shared code, state, or APIs that could break other
  paths.
- If a shared change is unavoidable: it must be backwards-compatible, and
  every pre-existing test must pass **unchanged**.
- Migration of file locations happens only inside a feature you are already
  touching — never as a standalone mass rename.

## 3. Layers (Android: ui = api, core = core, data = db, client/facade = leaves)

```
ui ──► core ──► model          ui may compose anything via public surfaces
         ├─► facade (wire↔model mapping)
         └─► data ──► raw storage leaf
client (external services) = leaf
```

- `ui/` is the only layer touching `android.view`/`android.widget`.
- `core/` is pure: no view, no storage, no network; throws typed exceptions.
- `client/` maps transport failures to typed exceptions
  (`ApiException`, `NetworkException`, `ParseException`, `ImportCancelled`).
- `CancellationException` always propagates; cancelled requests leave no
  partial side effects.
- Never log API keys, prompts, or page images. Secrets stay Keystore-backed.

## 4. Definition of done (every feature)

- [ ] Own folder/files; nothing bolted onto an existing class.
- [ ] Zero changes to unrelated files; existing tests unchanged and green.
- [ ] Only public-surface imports; graph acyclic.
- [ ] Typed errors at boundaries, user copy at the UI, cancellation safe.
- [ ] New tests through public surfaces; full suite green; `lint` clean.
- [ ] `docs/` updated where behaviour diverged.

## 5. Quick references

- Monorepo: `app/` = Android frontend (the product, offline-first);
  `server/` = backend template, absent today, additive-only when it lands.
- Known debt: D1 `ui/NotebookScreen` god-object (split in M4/M5) — do not
  grow it further; D4 `AnswerParser` parse+mapping merge (split when it grows).
- Environment caveat: sandboxed emulators render PDFs black via `PdfRenderer`
  (see `PdfTestSupport`) — pixel assertions are env-gated, never "fixed" by
  weakening them; verify on real hardware.
