import zlib

import numpy as np

from experiments import live_asr
from stt_button import asr

SR = asr.SAMPLE_RATE


def speech_like(seconds, silences=()):
    """Loud tone everywhere except the given (start_s, end_s) silences."""
    t = np.arange(int(seconds * SR)) / SR
    samples = 0.3 * np.sin(2 * np.pi * 220 * t).astype(np.float32)
    for start, end in silences:
        samples[int(start * SR):int(end * SR)] = 0
    return samples


def token(chunk: np.ndarray) -> str:
    digest = zlib.adler32(np.ascontiguousarray(chunk).tobytes()) & 0xFFFFFFFF
    return f"w{digest:08x}"


def test_commit_points_follow_the_slicer():
    samples = speech_like(25, ((9.3, 9.6), (19.1, 19.4)))
    replay = live_asr.simulate(samples, 0.5, 5, lambda chunk: "x")

    bounds = asr.slice_bounds(samples, live_asr.LIVE_SLICE_MAX_S, 5)
    assert replay.commits == bounds
    assert len(replay.commits) < len(replay.ticks)


def test_tail_covers_only_uncommitted_audio():
    samples = speech_like(12)
    seen = []

    def recognize(chunk):
        seen.append(np.copy(chunk))
        return token(chunk)

    replay = live_asr.simulate(samples, 1.0, 5, recognize)
    assert len(seen) == len(replay.calls)
    frontier = 0
    tails = 0
    for call, audio in zip(replay.calls, seen, strict=True):
        assert np.array_equal(audio, samples[call.start:call.end])
        assert call.start == frontier
        if call.kind == "tail":
            assert call.end - call.start >= live_asr.MIN_TAIL_SAMPLES
            tails += 1
        else:
            assert call.kind == "commit"
            frontier = call.end
    assert frontier == len(samples)
    assert tails >= 1
    assert replay.ticks[-1].end == len(samples)
    assert [(c.start, c.end) for c in replay.calls if c.kind == "tail"] == [
        t.tail for t in replay.ticks if t.tail]

    commit_text = {
        (call.start, call.end): token(samples[call.start:call.end])
        for call in replay.calls if call.kind == "commit"
    }
    assert len(replay.displayed) == len(replay.ticks) + 1
    for tick, text in zip(replay.ticks, replay.displayed):
        so_far = [commit_text[(a, b)] for a, b in replay.commits if b <= tick.commit_end]
        committed = asr.join_slices(so_far)
        if tick.tail is None:
            assert tick.end - tick.commit_end < live_asr.MIN_TAIL_SAMPLES
            assert text == committed
        else:
            assert tick.tail == (tick.commit_end, tick.end)
            assert text == asr.join_slices([committed, token(samples[tick.tail[0]:tick.tail[1]])])
    assert replay.displayed[-1] == replay.committed_text


def test_tail_shorter_than_0_3s_is_skipped():
    n_short = live_asr.MIN_TAIL_SAMPLES - 1
    n_exact = live_asr.MIN_TAIL_SAMPLES

    def run(n):
        seen = []
        samples = np.zeros(n, dtype=np.float32)

        def recognize(chunk):
            seen.append(len(chunk))
            return "w"

        replay = live_asr.simulate(samples, 10.0, 5.0, recognize, slice_max_s=30)
        return seen, replay

    seen, replay = run(n_short)
    assert seen == [n_short]
    assert replay.calls == [live_asr.Decode("commit", 0, n_short)]
    assert replay.ticks[0].tail is None

    seen, replay = run(n_exact)
    assert seen == [n_exact, n_exact]
    assert replay.ticks[0].tail == (0, n_exact)
    assert [c.kind for c in replay.calls] == ["tail", "commit"]


def test_churn_counts_a_retracted_word_and_ignores_pure_appends():
    assert live_asr.retracted_words(["alpha beta", "alpha beta gamma", "alpha beta gamma delta"]) == 0
    assert live_asr.retracted_words(["alpha beta", "alpha gone"]) == 1
    assert live_asr.retracted_words(["alpha beta gamma", "alpha x gamma"]) == 2
    assert live_asr.churn_per_100(["alpha beta", "alpha beta gamma"], "alpha beta gamma") == 0
    assert live_asr.churn_per_100(["alpha beta", "alpha gone"], "alpha gone") == 50

    samples = np.ones(SR, dtype=np.float32)

    def replay_script(script):
        seq = iter(script)
        replay = live_asr.simulate(samples, 1.0, 5.0, lambda chunk: next(seq))
        return replay, live_asr.churn_per_100(replay.displayed, replay.committed_text)

    retracted, churn = replay_script(["hello world", "hello there"])
    assert retracted.displayed == ["hello world", "hello there"]
    assert retracted.committed_text == "hello there"
    assert churn == 50

    appended, churn = replay_script(["hello", "hello world"])
    assert appended.displayed == ["hello", "hello world"]
    assert churn == 0


def test_final_committed_text_is_the_join_of_every_slice():
    samples = speech_like(25, ((9.3, 9.6), (19.1, 19.4)))
    replay = live_asr.simulate(samples, 0.5, 5, token)
    bounds = asr.slice_bounds(samples, live_asr.LIVE_SLICE_MAX_S, 5)
    assert replay.committed_text == asr.join_slices(token(samples[a:b]) for a, b in bounds)
    assert " " in replay.committed_text


def test_wer_is_measured_against_the_baseline_transcript():
    replay = live_asr.Replay(
        commits=[(0, 3)], calls=[], ticks=[], committed_text="the cat sat", displayed=["the cat sat"],
    )
    row = live_asr.metrics(replay, audio_s=2.0, decode_s=1.0, baseline="the dog sat down")
    assert row["ref_words"] == 4
    assert row["errors"] == 2
    assert row["wer"] == 50
    assert row["decode_per_audio_s"] == 0.5


def _result(u, decode_s, audio_s, retracted, final, errors, ref):
    return {
        "u_s": u,
        "decode_s": decode_s,
        "audio_s": audio_s,
        "decode_per_audio_s": decode_s / audio_s,
        "retracted_words": retracted,
        "final_words": final,
        "churn_per_100": 100 * retracted / final,
        "errors": errors,
        "ref_words": ref,
        "wer": 100 * errors / ref,
    }


def test_summary_medians_and_pooled_totals():
    rows = [
        _result(0.5, decode_s=1, audio_s=1, retracted=0, final=1, errors=0, ref=1),
        _result(0.5, decode_s=9, audio_s=3, retracted=10, final=10, errors=5, ref=10),
        _result(1.0, decode_s=2, audio_s=2, retracted=1, final=4, errors=1, ref=4),
    ]
    groups = {g["u_s"]: g for g in live_asr.summarize(rows)}
    half = groups[0.5]
    assert half["n"] == 2
    assert half["median_decode_per_audio_s"] == 2
    assert half["total_decode_per_audio_s"] == 2.5
    assert half["median_churn_per_100"] == 50
    assert half["total_churn_per_100"] == 100 * 10 / 11
    assert half["median_wer"] == 25
    assert half["total_wer"] == 100 * 5 / 11
    assert groups[1.0]["median_wer"] == 25
