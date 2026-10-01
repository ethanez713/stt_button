# Local dictation

A personal Android dictation tool: while Gboard is in English, a button sits on Gboard's microphone, runs local speech recognition, then a small cleanup model, and types the result into whatever field is focused. On any other keyboard language the microphone is left uncovered, so Gboard's own voice typing still receives the tap. The host can record from this machine's microphone and run the same two stages on the recording, so model choices are tested here before anything is installed on the phone.

Wispr Flow is the behavior reference. Its architecture is recorded in `docs/research/wispr-flow.md`. Nothing in that build is reused: no code, no endpoints, no account, no analytics SDK.

## Constraints

- The phone build has no `INTERNET` permission, checked in the merged manifest, since a dependency's manifest can add it; the app manifest removes it with `tools:node="remove"`. Model files are copied on with adb. The only way logs leave the phone is `adb exec-out run-as` against a debuggable build.
- `android:allowBackup="false"` and `dataExtractionRules` excluding all domains, because Android's system backup and device transfer would otherwise copy the edit log off the phone without any app permission.
- Weights and libraries are free to use and ship. Parakeet Unified is under the NVIDIA Open Model License, which permits use and redistribution with the license and its attribution notice kept. The preview model is sherpa-onnx's conversion of NVIDIA's `stt_en_fastconformer_hybrid_large_streaming_multi`, under CC-BY-4.0, which requires crediting NVIDIA. The about screen carries the licenses and notices. S1-mini is Apache-2.0 plus a naming clause: wherever the weights are used they stay "S1-mini" by "Superwhisper", capitalization included, with the Apache `LICENSE` and `NOTICE` retained.
- The accessibility process stays resident and does not hold a model between takes. The recognizer and the preview model are loaded when a take opens, the cleaner once the take has recorded `cleaner_load_after_s` of audio or a landed slice's text needs cleaning (a self-correction cue, or more than 40 words), and all are released once the text is inserted or the take is discarded. A take that needs the cleaner earlier than that waits for its load after the stop.
- The recognizer and the cleaner are a Python library with a command-line entry on this machine. The Android app runs the same stages in Kotlin over the same model files. The values both sides must agree on — the system prompt, the control line, the take cap, slice length, the pause rule's thresholds, the cleaner's chunk sizes, and the skip rule's word cap and self-correction cues — live in `config/pipeline.json`, which the library reads and the Android build packages as an asset.

## Pipeline

```
16 kHz mono PCM  →  Parakeet Unified EN (sherpa-onnx)  →  raw text  →  S1-mini  →  cleaned text
```

A take lasts at most 5 minutes. Audio is appended to a file while the take is open. The recognizer runs on successive slices of that file during the take, each slice at most 30 seconds of audio, and the raw text is concatenated. A slice ends early on a pause, so recognition keeps up while the user thinks. The audio is measured in 100 ms windows aligned to the start of the take, each window's RMS computed in double from the samples scaled to ±1. A window is silent when its RMS is below the noise floor plus `pause_margin_db`, where the floor is the 10th percentile (sorted index `floor(0.1 × (n − 1))`) of the window RMS values so far, never below −70 dBFS. At each window boundary, a slice that holds at least `slice_min_s` of audio, at least one non-silent window, and ends in `pause_s` of silent windows is cut at the middle of that silent run. A slice that reaches 30 seconds without a pause is cut at the quietest 100 ms of its last 5 seconds, so a word is not split. Feeding the audio in pieces gives the same cuts as feeding it whole. A pause never ends the take, though a long one inserts what has been said (§ Segments). Slicing keeps the recognizer's input small and leaves only the last slice to run after the stop tap.

S1-mini cleans during the take too, in chunks of whole sentences. Each time a slice lands, the raw text so far is split into sentences and the last one is held back, because the next slice may continue it. The sentences before it that no chunk has taken close a chunk once they reach 128 tokens, and that chunk is cleaned straight away. After the stop, the last slice is recognized and every remaining sentence is cleaned as the final chunk, whatever its size. A single sentence over 1,000 tokens is split at word boundaries, since S1-mini is trained on inputs of about that size. The inserted text is the cleaned chunks in order, separated by a blank line: a space would glue the sentence after a list onto its last item, and a whole pass puts paragraph breaks about that often anyway. There is no whole-transcript pass after the stop. In a blind vote on the user's rambles, 128-token chunks were no worse than one whole pass (chunked 4, whole 3, tie 2); 64-token chunks lost 4 to 7.

A take of 40 words or fewer is inserted as recognized, without S1-mini, unless it contains a self-correction cue from `config/pipeline.json`. Up to 40 words, S1-mini changed only punctuation and number formatting on the user's short takes, while resolving a correction ("at four, actually no, make it five") is its job. The cue list errs wide, because a false match costs one short clean and a miss inserts the uncorrected text. Both are judged on the raw text of the whole take so far (§ Segments). Every take of 40 words or fewer, raw or cleaned, is inserted without its commas (those inside numbers stay) and its final period, which is how the user writes messages; question marks, capitals and periods between sentences stay.

Spoken punctuation is converted before the cleaner and on the path that skips it: "quote … unquote" and "open/close paren" pair into symbols, "question mark" and "exclamation point" end the sentence, and "new line" and "new paragraph" break it. A command without its partner within 30 words stays words, and bare "period" and "comma" are never commands, because they are too often meant literally. The command list and pairing rules are `stt_button/spoken.py`, and the phone's port reads the same vectors.

### Segments

A take is a run of segments, and a segment is the audio since the take opened or since the last insert. When the take has been silent for `insert_pause_s` (2 s) and the segment holds recognized words, the worker recognizes the slices still queued, finishes the segment exactly as the stop finishes a take, inserts its text, and keeps recording. The stop tap finishes the last segment the same way, and a segment without words inserts nothing. Each segment is cleaned on its own text, with its own chunks and idle-clean cache. The skip rule and the casual rule are judged on the raw text of the whole take so far, every segment up to and including this one, so a dictation stays short and casual until it passes `skip_max_words` words or says a cue, and from then on its segments are cleaned. Consecutive segments are inserted with a single space between them. `insert_pause_s` is longer than `pause_s`, so a segment's audio is already cut into slices when its trigger fires. Segments are phone-only; the desktop pastes once at the stop.

Slices and chunk cleans run one at a time, in the order they become ready, on one worker thread. On the phone, running both at once slowed the cleaner's decoding 3 to 14 times. The same collapse follows from giving the cleaner more threads than the cores the system lets the process use at that moment (4 of 8 while another app is in the foreground), because its threads spin at a barrier after every operation and starve the ones with work: 9 s became 130 s for one transcript. The cleaner takes the process's allowed core count at the start of each chunk and uses at most that many of its 6 threads, on a persistent pool.

While the take is paused (its last `pause_s` of audio is silent) and the worker has nothing queued, the worker cleans the chunks `live_chunks(raw, final=True)` yields from the raw text so far, and keeps each output under its exact chunk text. A non-silent window cancels the clean in progress and discards its output. After the stop, a chunk whose text matches a kept output uses it instead of being cleaned again, so the inserted text is the text the take would have produced without idle cleaning, and a take that ends in a pause inserts as soon as its last slice lands. No idle clean runs while the skip rule applies to the text so far.

A second, streaming recognizer feeds the live preview (§ Preview bubble) and nothing else: NeMo's cache-aware streaming FastConformer transducer, 480 ms chunks, int8 (`sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8`, 131 MB encoder), on 2 threads. Its text is lowercase, unpunctuated and less accurate than Parakeet's (25% against 11% WER on the read passages), so it is never inserted. It costs 3–7% of two cores while the user speaks. Parakeet cannot stream cheaply: sherpa-onnx's streaming Unified recomputes about 5.6 s of left context per chunk and runs slower than real time, and re-decoding its uncommitted tail every second costs 30–40% of six cores.

Parakeet Unified EN 0.6B (`nvidia/parakeet-unified-en-0.6b`), int8 through sherpa-onnx's prebuilt non-streaming package (about 500 MB), is the recognizer. It is English-only and emits English letters with punctuation and capitalization. Its published Open ASR Leaderboard average is 5.91% WER, against 6.05% for Parakeet TDT 0.6B v2 and 6.34% for v3. Parakeet Ultra, Moondream's CC-BY-4.0 post-training of v3, publishes 5.80% on a seven-set average under a newer scoring pipeline, which does not compare directly. English-only is a preference, not a requirement, so the host comparison in `docs/plans/` measures Unified, TDT v2, and Ultra on the user's own speech and picks the most accurate. sherpa-onnx is the runtime because a published Galaxy S10 run finishes 30 seconds of audio in about 2.8 seconds with a 0.6B Parakeet, and the same run put one whisper.cpp build 50 times behind sherpa-onnx on Whisper Tiny. The models that lead the leaderboard, Cohere Transcribe (2B) and Canary-Qwen (2.5B), decode autoregressively at three to four times this size, which is the wrong trade for a phone.

S1-mini Q4_K_M (462 MB GGUF, a Qwen3-0.6B fine-tune, run with llama.cpp) is the cleaner. It removes fillers, resolves a self-correction to the later phrase, and writes punctuation, numbers, dates, currency, and email addresses. Decode is greedy at temperature 0 with thinking disabled, and `max_new_tokens` is `1.3 × input_tokens + 32`. On the phone it uses prompt-lookup decoding (llama.cpp `--spec-type ngram-simple`, 3-token match, 16-token draft): the tokens that followed the last three generated tokens' earlier occurrence in the prompt are proposed, and the model keeps the prefix it agrees with. Cleaning copies most of the transcript, so 59% of drafts are accepted and decoding runs 1.67× faster at the median. Batched verification uses different kernels from one-token decoding, so near-tie choices can flip: 15 of 22 outputs match plain decoding, and the rest differ by a filler word, a comma or a capital. The phone's plain decoding already differs from the host's on 3 of 10 transcripts, so no host run reproduces the phone's text exactly either way. The system prompt is copied verbatim from the `superwhisper/s1-mini` model card; it and the control line are the training format, and changing the wording makes it hallucinate.

```
[Styling: semi-formal] [Structure: lists] [Context: general]
<raw transcript>
```

The control line is the same for every app. `lists` is the standing structure value because the model leaves non-enumerations as prose. `general` is the context everywhere, because `email` reshapes every output into a greeting, body, and sign-off. The cleaner receives the recognizer text unchanged. Both strings are kept.

Qwen3.5-2B, run as a single instruct call in S1-mini's place with thinking disabled, lost all ten blind comparisons on the user's own rambles and took twice as long; it invented facts (a correction became a destination) and bulleted prose. Qwen3.5-0.8B is not better than S1-mini at this job either. A replacement cleaner has to beat S1-mini in the same blind vote over the same recordings, with the edit log as the check once the phone is in use.

## Host library

Python, run with `pyrun`, in this repo. Models live outside the repo in a directory given by `STT_MODEL_DIR`. A missing model is a clear error, not a download.

```
transcribe(wav_path) -> RawTranscript(text, elapsed_ms)
clean(raw) -> Cleaned(text, elapsed_ms)
run(wav_path) -> Result(raw, cleaned, asr_ms, clean_ms)
```

`transcribe` slices the wav exactly as the phone slices a take, and `clean` chunks exactly as the phone does, so a host run measures what the phone will produce. `python -m stt_button <wav>` prints the result as one JSON object. `python -m stt_button.record` records a take from this machine's microphone through WSLg's PulseAudio server into `data/recordings/`, which is gitignored. Tests cover the control line, slicing, and chunking with the models absent. Timing tests are marked slow and skip when `STT_MODEL_DIR` is unset.

## Desktop dictation

The same two stages serve dictation on this Windows machine: Ctrl+Alt+Space starts a take, a second press pastes the cleaned text into the focused app, and Ctrl+Alt+Shift+Space cancels. The Windows helper (`desktop/SttHotkey.cs`, built by `desktop/install.sh` with the `csc.exe` that ships with Windows) owns the hotkeys, the microphone, a focus-neutral indicator, and the paste. It records through the waveIn API straight to a file, so "Recording" shows the instant the key is pressed, and it keeps the microphone open for 5 minutes after a take so the next one starts without opening the device, with 0.3 s of pre-roll so a first word spoken with the keypress is kept; Windows shows the microphone as in use for those 5 minutes. The WSL side (`stt_button/dictate.py`) starts one worker per take that follows the growing file and runs the phone's pipeline: completed slices are recognized and closed chunks cleaned during the take, one at a time, and after the stop the last slice and the remaining chunk, under the same skip rule. The helper's indicator is also the preview bubble: while a take is open it shows the last four lines of what has been heard, as § Preview bubble describes, never taking focus. The worker runs the FastConformer preview on its own thread and rewrites a small bubble file on each change, which the helper reads. Prompt-lookup decoding is phone-only; the desktop cleaner has no use for it at GPU speed. Nothing runs between takes except the helper. As on the phone, the worker loads the cleaner alongside the recognizer, so no model load waits until after the stop. Takes and a per-take log line stay in `data/dictation/`, gitignored, with audio kept to 10 minutes as on the phone.

The desktop cleaner runs S1-mini on the GPU through the local Ollama server (`Cleaner(backend="ollama")`, the same GGUF and the same rendered prompt in raw mode), loaded when the take starts and unloaded after it, and falls back to the in-process CPU backend when Ollama does not answer. The GPU's advantage depends on the laptop's Lenovo thermal mode: in Performance mode it generates ~187 tokens/s against the CPU's ~50, but the quieter modes hold it near 990 MHz, where it drops to ~41. GPU and CPU outputs are identical on 15 of 22 transcripts and differ by a filler word or punctuation on most of the rest; the phone, which has no such GPU path, matches the CPU backend except where prompt lookup flips a near-tie.

## Phone app

Package `com.ezhou.sttbutton`, minSdk 33, targetSdk 36, `android:isAccessibilityTool="true"`. The test device is the SM-S938U. Its enabled Gboard layouts are English (US), 简体中文拼音, and 简体中文手写.

The accessibility service, the overlay, the recorder, and the model runtimes live in one process. Audio stays in a file: 16 kHz mono PCM16 is 32 KB per second, so a 5-minute take is about 9.6 MB. Passing that audio across a binder transaction would fail near 30 seconds, which is why recognition reads the file in-process. The microphone foreground service is held for the whole take, which is also what keeps the process from being cached while the models are loaded. A native crash drops the service and the system restarts it.

Sideloaded apps on Android 13 and later cannot enable an accessibility service until the user opens the app's system page and allows restricted settings. The first run on this phone includes that step, plus the Samsung unrestricted-battery setting. Until all three models' files are present in the app's files dir, the overlay stays off, so a missing model cannot block Gboard's microphone.

### When the button shows

The button shows whenever a keyboard window is up, unless something positively rules it out: a model file is missing, the user hid it (see § Where the button sits), the active IME subtype resolves to a language other than `en`, the focused field belongs to this app, or the focused field is a password, number, phone, or date input. Anything the service cannot read — an unresolved subtype, no focused node, a node that reports no input type or no editability — shows the button, because web fields such as google.com's search box report little, and a wrongly shown button is one drag away while a wrongly hidden one cannot be used at all.

The active subtype is the integer in `Settings.Secure` `selected_input_method_subtype`, matched to a subtype of `DEFAULT_INPUT_METHOD` by `InputMethodSubtype.hashCode()` and by explicit subtype id. On this phone those two values are the same for the enabled layouts (English (US) `1594443099`, 拼音 `617035939`, 手写 `1458747958`); the ids are not constants to hard-code, because they are per-install hashes. The language subtag comes from `getLanguageTag()` when that string is non-empty, otherwise from `getLocale()`. The enabled English layout on this phone has tag `en-US`. Gboard also registers implicit subtypes with an empty language tag and locale `en_US`; a matcher that ignores locale reports those as unknown. The physical-keyboard tag is not the language: the enabled 拼音 subtype is locale `zh_CN` with physical-keyboard tag `en`, and covering the mic there would steal Chinese dictation.

A language subtag other than `en` removes the overlay. A missing match, an empty locale and tag, or a value of `-1` is unresolved and leaves it showing. An invisible view left at the mic's bounds would still take the tap, so removal is the whole behavior. A `ContentObserver` on `selected_input_method_subtype` is what notices a globe or space-bar language change; `TYPE_WINDOWS_CHANGED` does not fire for that. When the subtype leaves English during an open take, the overlay is removed immediately and the take is discarded without insertion.

The accessibility config matches what the overlay and insertion require: `canRetrieveWindowContent`, `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`, `FLAG_REPORT_VIEW_IDS`, `FLAG_INPUT_METHOD_EDITOR`. `FLAG_REQUEST_FILTER_KEY_EVENTS` is omitted until a concrete key event is needed. `canPerformGestures` is omitted. The static event types are `TYPE_WINDOWS_CHANGED`, which is when the window list and the microphone bounds are read, and `TYPE_VIEW_FOCUSED`, because moving between two fields while the keyboard stays up (username to password) changes no window. `TYPE_VIEW_TEXT_CHANGED` is enabled at runtime only for a field this app just wrote into, and cleared when that field blurs.

### Where the button sits

The button is a `TYPE_ACCESSIBILITY_OVERLAY` added from the accessibility service, `FLAG_NOT_FOCUSABLE` and `FLAG_NOT_TOUCH_MODAL`, without `FLAG_ALT_FOCUSABLE_IM`, so it is drawn above the IME and a tap on it does not steal the cursor. `FLAG_NOT_FOCUSABLE` combined with `FLAG_ALT_FOCUSABLE_IM` would place it behind the keyboard, which is the wrong layer.

While the subtype is English and the voice-typing node has bounds, the button matches those bounds (`com.google.android.inputmethod.latin`, content description for voice typing), and the bounds are read again on each window change. The tap hits this app and Gboard's own voice typing does not start.

When that node has no bounds, the button is placed at the top-right of the IME window, which is where Gboard's toolbar microphone usually sits. That spot is a guess. The button can be dragged whenever it is showing, and it stays where the finger releases, including off the keys. A later window change does not move it back onto the keyboard. After it has been dragged clear of the microphone, taps on the microphone reach Gboard.

While the button is dragged, two drop zones appear along the bottom edge of the screen. Dropping on Hide (left half) removes the button everywhere until "Show the button again" in the app. Dropping on Reset (right half) clears the dragged position, so the button returns to the microphone. A tap briefly darkens the button, which is the tap's only feedback that needs no notification permission; Android suppresses toasts from an app whose notifications are off.

Tap starts a take. Tap again stops it; the last slice is recognized and the last chunk cleaned (§ Pipeline). Silence of `insert_pause_s` inserts the text so far and the take continues (§ Segments). A pause in speech does not stop a take. At 5 minutes the take stops as if tapped. If the focused window's package changes during a take, or no editable field is focused when the cleaned text is ready, the take is discarded without insertion. A keyboard window that stays gone for 700 ms stops the take as a tap would, and the text is inserted only if the field it started in is still focused. Switching the keyboard off English removes the button and discards the open take without inserting, because the microphone has to be left uncovered. While a take is open the button stays on screen so it can be tapped to stop. From the stop tap until insertion the button shows it is busy and ignores taps. A process death during a take loses that take: its audio file is deleted when the service next starts, and the `exit` line in the edit log records why. Audio is 16 kHz mono PCM16 from `AudioRecord`, appended to a file in the app's files dir. A microphone foreground service runs only while a take is open.

### Preview bubble

While a take is open, a bubble above the keyboard shows what has been heard. The field is not edited while the user is speaking: text is inserted at the stop and after each silence of `insert_pause_s` (§ Segments), and the bubble then shows only the segment still open. The bubble is a second `TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_FOCUSABLE` and `FLAG_NOT_TOUCHABLE`, so taps pass through it to the app beneath. It spans the IME window's width, sits directly above that window's top edge, and shows the last four lines of text. It appears with the take and goes when the text is inserted or the take is discarded.

It holds, in order:
- cleaned chunks, each replacing the raw sentences it was made from;
- Parakeet's text for recognized slices not yet cleaned;
- the preview model's text for the audio after the last recognized slice, drawn lighter.

When a slice lands, the preview words whose timestamps fall inside it are dropped and Parakeet's text takes their place.

### Recordings

After insertion, each take's PCM file is encoded losslessly to `files/recordings/<id>.flac`, named by its edit-log id, and the PCM file is deleted. The encoder is Android's built-in `MediaCodec` FLAC encoder; its codec-config buffer (the `fLaC` stream header) followed by its output frames is the file, since `MediaMuxer` has no FLAC container. Recordings are kept up to 10 minutes of total audio, oldest deleted first, which is roughly 10 MB as FLAC against 19 MB as PCM. A discarded take's audio is deleted at once. Pull with `adb exec-out run-as com.ezhou.sttbutton tar -cf - files/recordings > recordings.tar`, and decode to wav on the host with `flac -d` before replaying through the library.

### Insertion

The utterance is inserted at the caret. Characters already in the field stay. A selection is collapsed to its end before the insert, so highlighted text is kept. The paths, in order:

1. `commitText` on `AccessibilityService.getInputMethod()`'s current connection.
2. Clipboard: save the current clip, paste at the caret, restore the saved clip. If the service cannot read the current clip, this path is skipped rather than overwriting the user's clipboard.
3. `ACTION_SET_TEXT` with the previous text and the utterance added at the caret. It rewrites the whole field, which is why it is last.

A path is skipped when it is unavailable (a null connection, an unreadable clip) and abandoned when its call returns failure; each is tried once. A read-back that does not contain the utterance is logged, and the field is left as it stands rather than written again.

### Edit log

Append-only JSONL under the app's files dir, `logs/edits.jsonl`. One segment that is inserted (a take with no pause has one):

```
{"event":"insert","id":"<uuid>","ts":"<iso8601>","pkg":"<package>","raw":"...","cleaned":"...","text_before":"...","asr_ms":0,"clean_ms":0,"asr_backend":"cpu","clean_backend":"cpu","audio_ms":0,"in_tokens":0,"out_tokens":0,"charge_uah":0,"counter_used_uah":0,"plugged":false,"battery_pct":0,"clean_mode":"chunks","chunks":0,"idle_cleans":0,"idle_hits":0,"cue":null,"segment":0,"trigger":"stop","stop_to_insert_ms":0,"peak_pss_kb":0,"avail_mem_min_kb":0,"low_memory":false,"trim_level_max":0,"cpu_ms":0,"thermal_max":0}
{"event":"edit","id":"<same uuid>","ts":"<iso8601>","text_after":"..."}
```

Every take first writes `{"event":"start","id":"<uuid>","ts":"<iso8601>","pkg":"<package>"}`, and a take that inserts nothing, or whose last segment is lost to a language or package change, writes `{"event":"discard","id":"<same uuid>","ts":"<iso8601>","reason":"language|package|no_field|empty|error"}`. A take whose stop finds an empty segment after an earlier insert writes nothing more. A `start` followed by neither line is a take the process died in. Every `insert` of a take carries the take's id, `segment` counts from 0, and `trigger` is `pause`, `stop` or `keyboard`; `stop_to_insert_ms` runs from that trigger, and `edit` is written once per take.

`cue` is the first self-correction cue the raw text matched, or null. The latency, backend, battery, memory, CPU and thermal fields are per take. An `exit` line records each death of the app's process. `docs/plans/07-usage-monitoring.md` specifies how all of these are measured. `text_before` is the field contents at insert time. While the field is tracked, the service keeps its latest non-empty text from `TYPE_VIEW_TEXT_CHANGED`. `text_after` is that snapshot, written when the field loses focus or the focused window's package changes, whichever comes first, so a field that clears on send still records what was sent. Send buttons are not reliably visible, and some apps destroy the field on send without a focus event. Each line is one JSON object with escaped strings. Lines are written for fields this app inserted into. Other typing is not recorded. Password, numeric, and phone fields never reach this path because the button never appears there.

Pull with:

```
adb exec-out run-as com.ezhou.sttbutton cat files/logs/edits.jsonl
```

### Battery

- No boot-time model load, no analytics, no wake lock held across takes.
- Accessibility work is event-driven. There is no polling watchdog.
- The recognizer and preview model are loaded for the length of a take, the cleaner from the moment a take needs it, and all are released after it. They are not held between takes: the phone has about 3.4 GB of its 11.4 GB available and already compresses about 5 GB, so holding 1.25 GB for a later take would push other apps out. Sizes: about 1.25 GB of model files (Parakeet 654 MB, S1-mini 462 MB, FastConformer 131 MB). The microphone foreground service keeps the process ahead of cached and background apps for low-memory kills, and the edit log's memory fields and `exit` lines record what a take actually costs.
- Samsung unrestricted battery is requested once so the accessibility process survives Doze. That is a setting, not a second monitor service.

## v1

Host recorder, library, and CLI, and the recognizer and cleaner comparisons run on recordings of the user's own speech; the phases are in `docs/plans/README.md`. Then the Android service that shows the button on an English keyboard, records, previews, recognizes, cleans in chunks with S1-mini, inserts once, and writes the edit log. Other keyboard languages keep Gboard's microphone.

Dictionary, snippets, per-app style editors, and a cleanup model for languages other than English wait until the edit log shows they are worth it.

## First device check

Before the recognizer is packaged into the APK, confirm on the SM-S938U:

- With Gboard on English (US), the voice-typing control has usable bounds in the accessibility tree, and a `TYPE_ACCESSIBILITY_OVERLAY` of that size receives the tap without moving the cursor. If the control has no bounds, the button appears at the top-right of the keyboard and can be dragged off it, after which the microphone receives taps.
- Switching to 拼音 and to 手写 changes `selected_input_method_subtype`, the overlay is gone on both, and a tap on the microphone starts Gboard voice typing. A switch that does not change the setting fails this check.
- With the model files absent, the overlay is absent on the English layout too.
- A tap on the overlay, with another app in the foreground, starts the microphone foreground service from the accessibility service and `AudioRecord` captures non-silent audio. Android silences background capture without a while-in-use exemption, and this app does not hold `SYSTEM_ALERT_WINDOW`.
- The service can read the current clipboard clip while another app is focused. If it cannot, the clipboard insertion path is skipped on this phone.

The library does not depend on this check.
