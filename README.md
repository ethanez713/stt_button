# stt_button

Personal, on-device dictation for Android. While Gboard is in English, a button covers its microphone, runs local speech recognition and a small cleanup model, and inserts the text into the focused field. Other keyboard languages leave that microphone alone.

The design is `docs/design.md`. Notes on the Wispr Flow build it is modeled after are `docs/research/wispr-flow.md`.

Work in flight is phased in `docs/plans/README.md`. To resume, run `git log --oneline -8` and `git status --short`, then open only the phase the index marks current.

The phone app has no network permission. Model files are not in git; the host library reads `STT_MODEL_DIR`. Host Python runs through `pyrun`; tests are `pyrun python -m pytest -q -m "not slow"`.
