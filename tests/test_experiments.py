import json

import pytest

from experiments import chunk_clean, paths, report_asr, report_clean, vote
from stt_button.config import REPO


def arm(wer_nonum, rtf):
    return {"wer_nonum": wer_nonum, "median_rtf": rtf}


class TestAsrVerdict:
    def test_lowest_wer_wins(self):
        summary = {"unified": arm(6.0, 0.05), "tdt-v2": arm(6.5, 0.04), "ultra": arm(5.5, 0.04)}
        assert report_asr.verdict(summary).startswith("ultra")

    def test_close_and_much_faster_arm_wins(self):
        summary = {"unified": arm(5.8, 0.09), "tdt-v2": arm(6.0, 0.05)}
        assert report_asr.verdict(summary).startswith("tdt-v2")

    def test_close_but_not_fast_enough_does_not_win(self):
        summary = {"unified": arm(5.8, 0.07), "tdt-v2": arm(6.0, 0.05)}
        assert report_asr.verdict(summary).startswith("unified")

    def test_english_only_breaks_a_near_tie(self):
        summary = {"ultra": arm(5.8, 0.05), "unified": arm(6.0, 0.05)}
        assert report_asr.verdict(summary).startswith("unified")

    def test_english_only_does_not_beat_a_clear_lead(self):
        summary = {"ultra": arm(5.0, 0.05), "unified": arm(6.0, 0.05)}
        assert report_asr.verdict(summary).startswith("ultra")


class TestBlindKey:
    def test_every_ramble_gets_both_arms_and_a_stable_order(self):
        ids = [f"m{n:02d}" for n in range(10)]
        key = vote.build_key(ids)
        assert sorted(key["order"]) == sorted(ids)
        assert all(sorted(labels.values()) == sorted(vote.ARMS) for labels in key["labels"].values())
        assert vote.build_key(list(reversed(ids))) == key

    def test_assignment_is_not_always_the_same_arm_as_a(self):
        key = vote.build_key([f"m{n:02d}" for n in range(20)])
        assert len({labels["A"] for labels in key["labels"].values()}) == 2

    def test_new_rambles_extend_the_key_without_reshuffling(self, tmp_path):
        path = tmp_path / "key.json"
        first = vote.load_or_create_key(["m01", "m02"], path)
        second = vote.load_or_create_key(["m01", "m02", "m03"], path)
        assert second["order"][:2] == first["order"]
        assert second["labels"]["m01"] == first["labels"]["m01"]
        assert json.loads(path.read_text()) == second

    def test_pending_resumes_after_voted(self):
        key = {"order": ["m02", "m01", "m03"]}
        assert vote.pending(key, [{"id": "m02"}]) == ["m01", "m03"]


class TestCleanVerdict:
    KEY = {"labels": {f"m{n:02d}": {"A": "qwen3.5-2b", "B": "s1-mini"} for n in range(12)}}

    def votes(self, a_wins, b_wins, ties=0):
        ids = iter(sorted(self.KEY["labels"]))
        return ([{"id": next(ids), "choice": "a", "usable": True} for _ in range(a_wins)]
                + [{"id": next(ids), "choice": "b", "usable": False} for _ in range(b_wins)]
                + [{"id": next(ids), "choice": "t", "usable": None} for _ in range(ties)])

    def test_two_thirds_of_decided_votes_switches(self):
        t = report_clean.tally(self.votes(8, 4), self.KEY)
        assert t["wins"] == {"s1-mini": 4, "qwen3.5-2b": 8}
        assert report_clean.verdict(t).startswith("switch")

    def test_just_under_two_thirds_keeps_s1(self):
        t = report_clean.tally(self.votes(7, 4), self.KEY)
        assert report_clean.verdict(t).startswith("keep")

    def test_ties_count_toward_minimum_but_not_share(self):
        t = report_clean.tally(self.votes(6, 3, ties=1), self.KEY)
        assert t["ties"] == 1
        assert report_clean.verdict(t).startswith("switch")

    def test_too_few_votes_is_undecided(self):
        t = report_clean.tally(self.votes(5, 0), self.KEY)
        assert report_clean.verdict(t).startswith("undecided")

    def test_votes_for_unknown_ramble_are_counted_as_unjoined(self):
        t = report_clean.tally([{"id": "zz", "choice": "a", "usable": True}], self.KEY)
        assert t["unjoined"] == 1 and sum(t["wins"].values()) == 0


def word_tokens(text: str) -> int:
    return len(text.split())


class TestCutChunks:
    def test_chunk_closes_exactly_at_k(self):
        text = "one two three four. five six seven eight. nine ten."
        assert chunk_clean.cut_chunks(text, word_tokens, 8) == [
            "one two three four. five six seven eight.",
            "nine ten.",
        ]

    def test_small_remainder_is_its_own_chunk(self):
        text = "one two three four five. six seven. eight."
        assert chunk_clean.cut_chunks(text, word_tokens, 6) == [
            "one two three four five. six seven.",
            "eight.",
        ]

    def test_sentence_longer_than_k_stays_whole(self):
        text = "one two three four five six. seven eight."
        assert chunk_clean.cut_chunks(text, word_tokens, 3) == [
            "one two three four five six.",
            "seven eight.",
        ]


class TestVotePaths:
    def test_no_args_use_the_bakeoff_files(self):
        args = vote.argument_parser().parse_args([])
        assert (args.a, args.b, args.votes) == (None, None, None)
        outputs, votes, key = vote.resolve_vote(args.a, args.b, args.votes)
        assert outputs == {arm: paths.CLEAN / arm for arm in vote.ARMS}
        assert votes == paths.VOTES
        assert key == paths.BLIND_KEY

    def test_each_votes_file_has_its_own_resumable_key(self, tmp_path):
        a, b = tmp_path / "whole", tmp_path / "chunk64"
        votes = tmp_path / "chunk64.jsonl"
        outputs, votes_path, key_path = vote.resolve_vote(a, b, votes)
        assert votes_path == votes.resolve()
        assert key_path == votes.resolve().with_name("chunk64_key.json")

        arms = tuple(outputs)
        made = vote.load_or_create_key(["m01", "m02"], key_path, arms=arms)
        assert vote.load_or_create_key(["m01", "m02"], key_path, arms=arms) == made
        assert set(made["labels"]["m01"].values()) == set(arms)

        _, _, other = vote.resolve_vote(a, tmp_path / "chunk128", tmp_path / "chunk128.jsonl")
        assert other == (tmp_path / "chunk128_key.json").resolve()
        assert not other.exists()

    def test_arms_inside_the_repo_are_labelled_relative_to_it(self):
        outputs, _, _ = vote.resolve_vote(REPO / "data/x/whole", REPO / "data/x/chunk", REPO / "data/x/v.jsonl")
        assert list(outputs) == ["data/x/whole", "data/x/chunk"]

    def test_a_b_and_votes_must_be_given_together(self, tmp_path):
        with pytest.raises(SystemExit, match="together"):
            vote.resolve_vote(tmp_path / "a", None, None)


def test_vote_tally_maps_letters_to_arms(capsys):
    key = {"labels": {"x": {"A": "/d/whole", "B": "/d/chunk64"}, "y": {"A": "/d/chunk64", "B": "/d/whole"}}}
    vote.print_tally(key, [{"id": "x", "choice": "b"}, {"id": "y", "choice": "a"}, {"id": "z", "choice": "t"}])
    assert capsys.readouterr().out.strip() == "chunk64 2, whole 0, tie 1"


class TestLiveTimeline:
    def test_a_chunk_still_cleaning_at_stop_delays_the_last_slice(self):
        from experiments import live_timeline as lt

        long_sentence = " ".join(["word"] * 200) + "."
        slices = [(0.0, 30.0, f"{long_sentence} Held back."), (30.0, 31.0, "End.")]
        first_free = 30.0 + lt.asr_s(30.0) + lt.clean_s(200)
        assert first_free > 31.0
        expected = first_free + lt.asr_s(1.0) + lt.clean_s(word_tokens("Held back. End.")) - 31.0
        assert lt.live(slices, word_tokens, k=64) == expected

    def test_a_skipped_take_waits_only_for_its_last_slice(self):
        from experiments import live_timeline as lt

        assert lt.live([(0.0, 3.0, "On my way.")], word_tokens, k=64) == lt.asr_s(3.0)
        corrected = [(0.0, 3.0, "Tuesday, actually no, Wednesday.")]
        assert lt.live(corrected, word_tokens, k=64) > lt.asr_s(3.0)


class TestBoundaryPunct:
    def test_a_false_break_and_its_capital_are_counted_and_j_fixes_the_capital_only_without_a_break(self):
        from experiments.boundary_punct import boundary_counts
        reference = "we can always update that later and then it works"
        counts = boundary_counts(["we can always update.", "That later and then it works"], reference)
        assert counts["breaks"] == 1 and counts["caps"] == 1 and counts["caps_after_j"] == 1
        counts = boundary_counts(["we can always update", "That later and then it works"], reference)
        assert counts["breaks"] == 0 and counts["j_fixed"] == 1

    def test_a_capital_the_reference_shares_is_a_j_harm_not_an_error(self):
        from experiments.boundary_punct import boundary_counts
        counts = boundary_counts(["send it to", "Paris today"], "send it to Paris today")
        assert counts["caps"] == 0 and counts["j_harms"] == 1


class TestPhoneUsage:
    @staticmethod
    def insert(i, **over):
        base = {"event": "insert", "id": f"t{i}", "cleaned": f"text {i}", "asr_backend": "cpu", "clean_backend": "cpu",
                "audio_ms": 60_000, "asr_ms": 6000, "clean_ms": 500, "out_tokens": 50, "charge_uah": 20_000,
                "counter_used_uah": 19_000, "plugged": False, "battery_pct": 80, "peak_pss_kb": 2_000_000,
                "avail_mem_min_kb": 900_000, "low_memory": False, "trim_level_max": 0, "cpu_ms": 30_000,
                "thermal_max": 0, "clean_mode": "chunks", "stop_to_insert_ms": 800, "cue": None}
        return {**base, **over}

    def test_plugged_takes_are_excluded_and_groups_use_medians(self):
        from experiments import phone_usage
        lines = [self.insert(1), self.insert(2, charge_uah=40_000), self.insert(3, charge_uah=60_000),
                 self.insert(4, plugged=True, charge_uah=1)]
        report = phone_usage.summarize(lines, capacity_mah=5000)
        assert "4 inserts, 1 excluded as plugged" in report
        assert "cpu/cpu: 3 takes" in report
        assert "charge_uah per audio minute: 40000" in report
        assert "battery % per 10 min: 8.00" in report
        assert "asr_ms per audio second: 100.0" in report

    def test_a_missing_field_shows_as_a_null_count_not_a_clean_table(self):
        from experiments import phone_usage
        lines = [self.insert(1), self.insert(2, charge_uah=None), {**self.insert(3), "thermal_max": None}]
        report = phone_usage.summarize(lines)
        assert "lines lacking a field: charge_uah 1, thermal_max 1" in report

    def test_an_exit_after_an_unfinished_start_is_a_died_take(self):
        from experiments import phone_usage
        lines = [
            {"event": "start", "id": "a"}, self.insert("a", id="a"),
            {"event": "start", "id": "b"},
            {"event": "exit", "reason": "LOW_MEMORY", "pss_kb": 3_100_000, "description": "lmkd"},
            {"event": "start", "id": "c"}, {"event": "discard", "id": "c", "reason": "language"},
        ]
        assert [(s["id"], e["reason"]) for s, e in phone_usage.died_takes(lines)] == [("b", "LOW_MEMORY")]
        report = phone_usage.summarize(lines)
        assert "take b died: LOW_MEMORY" in report and "process exits: LOW_MEMORY 1" in report

    def test_the_swap_table_counts_outcomes_timings_and_takes_without_an_ime_line(self):
        from experiments import phone_usage
        lines = [
            self.insert(1, ime_swap="ok", ime_swap_ms=300), self.insert(2, ime_swap="ok", ime_swap_ms=500),
            self.insert(3, ime_swap="failed", ime_swap_ms=None), self.insert(4, ime_swap="off", ime_swap_ms=None),
            {"event": "ime", "id": "t1", "ime_return": "subtype", "ime_return_ms": 120, "ime_subtype_ok": True,
             "ime_height_delta_px": 0},
            {"event": "ime", "id": "t2", "ime_return": "failed", "ime_return_ms": None, "ime_subtype_ok": False,
             "ime_height_delta_px": 40},
        ]
        report = phone_usage.summarize(lines)
        assert "keyboard swap: 4 takes" in report
        assert "ime_swap: failed 1, off 1, ok 2" in report
        assert "ime_return: failed 1, subtype 1" in report
        assert "ime_swap_ms: median 400 p90 500 over 2" in report
        assert "ime_return_ms: median 120 p90 120 over 1" in report
        assert "ime_subtype_ok false: 1" in report and "ime_height_delta_px non-zero: 1" in report
        assert "takes with a swap and no ime line: 1 (t3)" in report

    def test_the_field_path_table_counts_writes_holds_revisions_and_takes_the_field_ended(self):
        from experiments import phone_usage
        lines = [
            self.insert(1, insert_path="field", writes=6, writes_held=0, held_max_ms=0, trigger="stop"),
            self.insert(2, insert_path="field", writes=10, writes_held=2, held_max_ms=1500, trigger="stop"),
            self.insert(3, insert_path="ladder", trigger="stop"),
            {"event": "clipboard", "id": "t4", "trigger": "field"},
            {"event": "job", "id": "t1", "kind": "clean", "scope": "region", "outcome": "applied", "ms": 900},
            {"event": "job", "id": "t2", "kind": "clean", "scope": "region", "outcome": "dropped", "ms": 700},
            {"event": "job", "id": "t2", "kind": "clean", "scope": "chunk", "outcome": "applied", "ms": 300},
        ]
        report = phone_usage.summarize(lines)
        assert "field: 2 takes, median writes 8, 1 with a held write (longest 1500 ms)" in report
        assert "ladder: 1 takes" in report
        assert "takes ended by the field going away: 1 (t4)" in report
        assert "revisions: 1 takes revised, 1 dropped, median 900 ms" in report

    def test_the_lag_table_splits_the_first_slice_from_later_ones_and_reports_the_load(self):
        from experiments import phone_usage
        lines = [
            self.insert(1, insert_path="field"),
            {"event": "job", "id": "t1", "kind": "asr", "lag_ms": 1000},
            {"event": "job", "id": "t1", "kind": "asr", "lag_ms": 0},
            {"event": "job", "id": "t1", "kind": "asr", "lag_ms": None},
            self.insert(2, insert_path="field"),
            {"event": "job", "id": "t2", "kind": "asr", "lag_ms": 3000},
            {"event": "job", "id": "t2", "kind": "asr", "lag_ms": 800},
            self.insert(3, insert_path="ladder"),
            {"event": "job", "id": "t3", "kind": "asr", "lag_ms": 100},
            self.insert(4, insert_path="ladder"),
            {"event": "job", "id": "t4", "kind": "asr", "lag_ms": None},
            {"event": "job", "id": "t4", "kind": "asr", "lag_ms": 500},
            {"event": "job", "id": "t1", "kind": "load", "ms": 900},
            {"event": "job", "id": "t2", "kind": "load", "ms": 1100},
            {"event": "job", "id": "t1", "kind": "clean", "ms": 50, "lag_ms": 1},
        ]
        report = phone_usage.summarize(lines)
        assert "field first slice: median 2000 p90 3000 over 2" in report
        assert "field later slices: median 400 p90 800 over 2" in report
        assert "ladder first slice: median 100 p90 100 over 1" in report
        assert "ladder later slices: median 500 p90 500 over 1" in report
        assert "load ms: median 1000 over 2" in report
        assert "lag_ms:" not in phone_usage.summarize([self.insert(1)])

    def test_the_preview_table_reports_first_text_and_provisional_writes_per_insert_path(self):
        from experiments import phone_usage
        lines = [
            self.insert(1, insert_path="field", first_text_ms=400, provisional_writes=3),
            self.insert(2, insert_path="field", first_text_ms=800, provisional_writes=5),
            self.insert(3, insert_path="ladder", first_text_ms=2000, provisional_writes=0),
        ]
        report = phone_usage.summarize(lines)
        assert "field: first_text_ms median 600 p90 800 over 2, median provisional_writes 4" in report
        assert "ladder: first_text_ms median 2000 p90 2000 over 1, median provisional_writes 0" in report
        assert "preview:" not in phone_usage.summarize([self.insert(1, insert_path="field")])

    def test_a_report_of_takes_from_before_the_field_path_has_no_field_path_table(self):
        from experiments import phone_usage
        assert "field path" not in phone_usage.summarize([self.insert(1)])

    def test_a_report_of_takes_from_before_the_swap_has_no_swap_table(self):
        from experiments import phone_usage
        assert "keyboard swap" not in phone_usage.summarize([self.insert(1)])

    def test_an_ime_line_does_not_change_which_takes_the_process_died_in(self):
        from experiments import phone_usage
        lines = [
            {"event": "start", "id": "a"}, self.insert("a", id="a"),
            {"event": "start", "id": "b"},
            {"event": "ime", "id": "a", "ime_return": "subtype"},
            {"event": "exit", "reason": "LOW_MEMORY", "pss_kb": 1, "description": "lmkd"},
        ]
        assert [(s["id"], e["reason"]) for s, e in phone_usage.died_takes(lines)] == [("b", "LOW_MEMORY")]

    def test_a_take_that_ended_on_the_clipboard_or_without_text_did_not_die(self):
        from experiments import phone_usage
        lines = [
            {"event": "start", "id": "a"}, {"event": "clipboard", "id": "a", "reason": "no_field"},
            {"event": "exit", "reason": "USER_REQUESTED"},
            {"event": "start", "id": "b"}, self.insert("b", id="b"), {"event": "end", "id": "b", "trigger": "caret"},
            {"event": "exit", "reason": "USER_REQUESTED"},
        ]
        assert phone_usage.died_takes(lines) == []

    def test_an_end_line_costs_battery_without_counting_as_a_discarded_take(self):
        from experiments import phone_usage
        day = {"ts": "2026-10-02T10:00:00Z"}
        lines = [
            {**self.insert(1), **day},
            {"event": "end", "id": "t1", **day, "trigger": "caret", "charge_uah": 5_000, "cpu_ms": 4_000, "plugged": False},
        ]
        with_end = phone_usage.summarize(lines)
        without = phone_usage.summarize(lines[:1])
        assert "discarded or clipboard takes: 0, 0.0 mAh, 0 CPU s" in with_end
        mah = lambda report: float(report.split("unplugged total: ")[1].split(" mAh")[0])  # noqa: E731
        assert mah(with_end) == mah(without) + 5.0

    def test_totals_count_discarded_takes_and_cpu_outside_takes_within_one_process(self):
        from experiments import phone_usage
        start = lambda i, cpu, age_h, events: {  # noqa: E731
            "event": "start", "id": f"t{i}", "proc_cpu_ms": cpu, "proc_age_ms": age_h * 3_600_000, "proc_events": events}
        day = {"ts": "2026-10-02T10:00:00Z"}
        lines = [
            start(1, 1_000, 1, 100), {**self.insert(1), **day, "sampler_cpu_ms": 300},
            {"event": "discard", "id": "t1b", **day, "charge_uah": 5_000, "cpu_ms": 4_000, "plugged": False},
            start(2, 45_000, 3, 500), {**self.insert(2), **day, "plugged": True},
            # A restarted process: its counters are not comparable with the one before.
            start(3, 500, 0.1, 5),
        ]
        report = phone_usage.summarize(lines, capacity_mah=5000)
        assert "unplugged total: 25.0 mAh and 34 CPU s over 1 days (0.50% of the battery per day)" in report
        assert "discarded or clipboard takes: 1, 5.0 mAh, 4 CPU s" in report
        assert "cost sampler's own share of cpu_ms: 1.0%" in report
        assert "outside takes: 5.0 CPU s per hour and 200 accessibility events per hour, over 2.0 h" in report

    def test_first_inserts_are_split_by_whether_the_models_were_warm(self):
        from experiments import phone_usage
        lines = [
            {"event": "start", "id": "t1", "models_warm": False}, self.insert(1, segment=0, cpu_ms=60_000),
            {"event": "start", "id": "t2", "models_warm": True}, self.insert(2, cpu_ms=30_000),
            {"event": "start", "id": "t3", "models_warm": True}, self.insert(3, segment=1, cpu_ms=1),
        ]
        report = phone_usage.summarize(lines)
        assert "warm starts: 2 of 3" in report
        assert "[warm]: 500 over 1" in report and "[cold]: 1000 over 1" in report

    def test_cues_and_edited_inserts_are_counted(self):
        from experiments import phone_usage
        lines = [self.insert(1, cue="actually"), self.insert(2, cue="actually"), self.insert(3, cue="make it"),
                 {"event": "edit", "id": "t1", "text_after": "text 1 and more"},
                 {"event": "edit", "id": "t2", "text_after": "something else"}]
        report = phone_usage.summarize(lines)
        assert "cue matches: actually 2, make it 1" in report
        assert "edited afterwards: 1 of 2" in report

    def test_inserts_with_landed_count_over_1_are_named(self):
        from experiments import phone_usage
        lines = [
            self.insert(1, landed_count=2), self.insert(2, landed_count=1),
            self.insert(3, landed_count=None), self.insert(4, landed_count=3),
        ]
        report = phone_usage.summarize(lines)
        assert "inserts with landed_count > 1: 2 (t1, t4)" in report

    def test_takes_with_zero_ms_over_half_their_audio_are_named(self):
        from experiments import phone_usage
        lines = [
            self.insert(1, zero_ms=30_001, audio_ms=60_000),
            self.insert(2, zero_ms=30_000, audio_ms=60_000),
            {"event": "discard", "id": "silent", "zero_ms": 501, "audio_ms": 1000},
            {"event": "clipboard", "id": "clip", "zero_ms": 500, "audio_ms": 1000},
            {"event": "discard", "id": "quiet", "zero_ms": 500, "audio_ms": 0},
            self.insert(3),
        ]
        report = phone_usage.summarize(lines)
        assert "takes with zero_ms over half their audio_ms: 2 (t1, silent)" in report


class TestPhoneReplay:
    def test_only_takes_with_audio_are_listed_with_their_raw_text_and_what_ended_them(self, tmp_path):
        from experiments import phone_replay
        (tmp_path / "kept.pcm").write_bytes(b"")
        (tmp_path / "empty.pcm").write_bytes(b"")
        lines = [
            {"event": "start", "id": "pruned", "ts": "t0"}, {"event": "insert", "id": "pruned", "raw": "gone"},
            {"event": "start", "id": "kept", "ts": "t1"},
            {"event": "insert", "id": "kept", "raw": "Send it to Paris.", "trigger": "silence"},
            {"event": "edit", "id": "kept", "text_after": "Send it to Paris."},
            {"event": "start", "id": "empty", "ts": "t2"},
            {"event": "discard", "id": "empty", "reason": "empty", "trigger": "stop"},
            {"event": "exit", "reason": "USER_REQUESTED"},
        ]
        assert phone_replay.logged_takes(lines, tmp_path) == [
            {"id": "kept", "ts": "t1", "ended": "insert:silence", "raw": "Send it to Paris."},
            {"id": "empty", "ts": "t2", "ended": "discard:stop", "raw": ""},
        ]

    def test_a_word_diff_ignores_case_and_punctuation_and_names_each_side(self):
        from experiments import phone_replay
        assert phone_replay.word_diff("Putting it in the curring house chores.", "putting it in the recurring house chores") == [
            ("curring", "recurring")]
        assert phone_replay.word_diff("see the full shopping", "See the full shopping list") == [("", "list")]
        assert phone_replay.word_diff("Same words, here.", "same words here") == []

    def test_pcm_is_read_as_floats_and_an_odd_trailing_byte_is_dropped(self, tmp_path):
        import numpy as np

        from experiments import phone_replay
        path = tmp_path / "take.pcm"
        path.write_bytes(np.array([0, 16384, -32768], dtype="<i2").tobytes() + b"\x01")
        assert phone_replay.read_pcm(path).tolist() == [0.0, 0.5, -1.0]

    def test_a_replay_feeds_the_take_in_pieces_and_returns_the_recognized_text(self):
        import numpy as np

        from experiments import phone_replay
        from stt_button import config

        class Recognizer:
            def recognize(self, samples):
                return f"{len(samples)} samples"

        assert phone_replay.replay(Recognizer(), np.zeros(4000, dtype=np.float32), config.load()) == "4000 samples"


class TestTakeTails:
    @staticmethod
    def samples(*parts, extra=0):
        import numpy as np

        from stt_button import asr

        win = int(asr.CUT_WINDOW_S * asr.SAMPLE_RATE)
        chunks = [np.full(win * n, 8000 if kind == "tone" else 0, dtype="<i2") for kind, n in parts]
        if extra:
            chunks.append(np.full(extra, 8000, dtype="<i2"))
        return np.concatenate(chunks)

    def test_only_the_last_full_window_counts_and_it_uses_the_slicers_threshold(self):
        from experiments import take_tails

        assert take_tails.ends_in_speech(self.samples(("silence", 5), ("tone", 1)))
        # Earlier windows are speech; the stop landed in the silence after them.
        assert not take_tails.ends_in_speech(self.samples(("silence", 5), ("tone", 2), ("silence", 3)))
        # A flat take never clears its own floor, so a fixed loudness threshold would disagree.
        assert not take_tails.ends_in_speech(self.samples(("tone", 20)))
        # A loud tail shorter than one window is not a window.
        assert not take_tails.ends_in_speech(self.samples(("silence", 3), extra=800))
        assert not take_tails.ends_in_speech(self.samples(("tone", 1))[:100])

    def test_the_table_joins_pcm_to_the_log_and_says_when_stop_takes_are_few(self, tmp_path):
        import json

        from experiments import take_tails

        takes = tmp_path / "takes"
        takes.mkdir()
        (takes / "loud-stop.pcm").write_bytes(self.samples(("silence", 5), ("tone", 1)).tobytes())
        (takes / "quiet-stop.pcm").write_bytes(self.samples(("silence", 5), ("tone", 1), ("silence", 2)).tobytes())
        (takes / "walked.pcm").write_bytes(self.samples(("silence", 4)).tobytes())
        (takes / "start-only.pcm").write_bytes(self.samples(("silence", 2)).tobytes())
        (takes / "orphan.pcm").write_bytes(self.samples(("silence", 3), ("tone", 1)).tobytes() + b"\xff")
        lines = [
            {"event": "start", "id": "loud-stop"},
            {"event": "insert", "id": "loud-stop", "trigger": "stop", "cleaned": "hi"},
            {"event": "clipboard", "id": "quiet-stop", "trigger": "stop", "text": "held"},
            {"event": "discard", "id": "walked", "trigger": "silence", "reason": "empty"},
            {"event": "start", "id": "start-only"},
            {"event": "insert", "id": "no-audio", "trigger": "stop", "cleaned": "gone"},
        ]
        (tmp_path / "edits.jsonl").write_text("".join(json.dumps(line) + "\n" for line in lines))

        assert take_tails.report(tmp_path) == (
            "stop: 2 takes, 1 ending in speech\n"
            "silence: 1 takes, 0 ending in speech\n"
            "null: 1 takes, 0 ending in speech\n"
            "takes without a log line: 1\n"
            "fewer than 20 stop-triggered takes (2)\n"
        )

    def test_twenty_stop_takes_do_not_say_the_count_is_short(self, tmp_path):
        import json

        from experiments import take_tails

        takes = tmp_path / "takes"
        takes.mkdir()
        pcm = self.samples(("silence", 1)).tobytes()
        lines = []
        for i in range(20):
            (takes / f"t{i:02d}.pcm").write_bytes(pcm)
            lines.append({"event": "insert", "id": f"t{i:02d}", "trigger": "stop", "cleaned": "x"})
        (takes / "mic.pcm").write_bytes(pcm)
        lines.append({"event": "clipboard", "id": "mic", "trigger": "mic", "text": "x"})
        (tmp_path / "edits.jsonl").write_text("".join(json.dumps(line) + "\n" for line in lines))

        text = take_tails.report(tmp_path)
        assert "stop: 20 takes, 0 ending in speech" in text
        assert "mic: 1 takes, 0 ending in speech" in text
        assert "fewer than 20" not in text
        assert "takes without a log line: 0" in text

    def test_a_missing_directory_says_there_are_not_enough_stop_takes(self, tmp_path):
        from experiments import take_tails

        assert take_tails.report(tmp_path / "absent") == (
            "takes without a log line: 0\n"
            "fewer than 20 stop-triggered takes (0)\n"
        )



def _take(take_id, cleaned, text_after, span=None, pkg="app", inserts=1, edit=True):
    lines = [{"event": "insert", "id": take_id, "pkg": pkg, "cleaned": cleaned, "clean_mode": "skip"} for _ in range(inserts)]
    if edit:
        start, end = span if span else (None, None)
        lines.append({"event": "edit", "id": take_id, "text_after": text_after, "span_start": start, "span_end": end})
    return lines


class TestCorrections:
    def classes(self, *takes):
        from experiments import corrections
        counted, skipped = corrections.analyse([line for take in takes for line in take])
        return {r["id"]: r["class"] for r in counted}, skipped

    def test_each_class(self):
        classes, skipped = self.classes(
            _take("a", "Hello there.", " Hello there.", (0, 13)),
            _take("b", "Hello there.", " hello there", (0, 12)),
            _take("c", "Send the phalanx report today.", " Send the failing report today.", (0, 31)),
            _take("d", "Send the report to Max.", " ok", (0, 3)),
        )
        assert classes == {"a": "untouched", "b": "marks", "c": "words", "d": "gone"}
        assert not skipped

    def test_letters_typed_against_the_span_make_a_replacement(self):
        classes, _ = self.classes(_take("a", "We research", "We researched it", (0, 11)))
        assert classes == {"a": "words"}

    def test_text_typed_after_the_span_leaves_the_take_untouched(self):
        classes, _ = self.classes(_take("a", "Hello there.", " Hello there. And more", (0, 13)))
        assert classes == {"a": "untouched"}

    def test_a_collapsed_span_is_gone(self):
        classes, _ = self.classes(_take("a", "Hello there.", "Other", (5, 5)))
        assert classes == {"a": "gone"}

    def test_not_counted_reasons(self):
        classes, skipped = self.classes(
            _take("a", "x y", "x y", (0, 3), inserts=2),
            _take("b", "x y", "", edit=False),
            _take("c", "x y", "x y"),
        )
        assert classes == {}
        assert dict(skipped) == {"several inserts": 1, "no edit line": 1, "null span": 1}

    def test_report_prints_the_zero_edit_rate_and_replaced_words(self):
        from experiments import corrections
        lines = _take("a", "Hello there.", " Hello there.", (0, 13)) + _take("c", "Send the phalanx report today.", " Send the failing report today.", (0, 31))
        text = corrections.report(lines)
        assert "zero-edit rate: 50% (1 of 2)" in text
        assert "c: phalanx → failing" in text


class TestConfigIds:
    def write(self, tmp_path, *ids):
        path = tmp_path / "edits.jsonl"
        rows = [{"event": "start", "id": f"t{i}", **({"config_id": c} if c else {})} for i, c in enumerate(ids)]
        path.write_text("".join(json.dumps(r) + "\n" for r in rows))
        return str(path)

    @pytest.mark.parametrize("module", ["corrections", "phone_usage"])
    def test_two_ids_stop_the_report_and_are_named(self, module, tmp_path, capsys):
        import importlib
        main = importlib.import_module(f"experiments.{module}").main
        assert main([self.write(tmp_path, "aaa", "aaa", "bbb")]) == 1
        out = capsys.readouterr().out
        assert "aaa 2 takes" in out and "bbb 1 takes" in out

    @pytest.mark.parametrize("module", ["corrections", "phone_usage"])
    def test_all_configs_pools_them(self, module, tmp_path):
        import importlib
        main = importlib.import_module(f"experiments.{module}").main
        assert main([self.write(tmp_path, "aaa", "bbb"), "--all-configs"]) == 0

    @pytest.mark.parametrize("module", ["corrections", "phone_usage"])
    def test_one_id_goes_on(self, module, tmp_path):
        import importlib
        main = importlib.import_module(f"experiments.{module}").main
        assert main([self.write(tmp_path, "aaa", "aaa")]) == 0

    def test_a_take_without_the_field_counts_as_unknown(self, tmp_path, capsys):
        from experiments import corrections
        assert corrections.main([self.write(tmp_path, None, "aaa")]) == 1
        assert "unknown 1 takes" in capsys.readouterr().out


class TestCorrectionReplay:
    def build(self, tmp_path, takes):
        """takes: (id, ts, cleaned, user_left); the recording's first sample is the take's index."""
        import numpy as np
        lines = []
        (tmp_path / "takes").mkdir(exist_ok=True)
        for index, (take_id, ts, cleaned, left) in enumerate(takes):
            samples = np.full(1600, index + 1, dtype="<i2")
            (tmp_path / "takes" / f"{take_id}.pcm").write_bytes(samples.tobytes())
            lines.append({"event": "insert", "id": take_id, "ts": ts, "pkg": "app", "cleaned": cleaned,
                          "text_before": "", "clean_mode": "skip"})
            text_after = " " + left
            lines.append({"event": "edit", "id": take_id, "text_after": text_after, "span_start": 0, "span_end": len(text_after)})
        return lines

    TAKES = [
        ("a", "2026-10-01T10:00:00Z", "Hello there.", "Hello there."),
        ("b", "2026-10-01T11:00:00Z", "Send the phalanx report.", "Send the failing report."),
        ("c", "2026-10-01T12:00:00Z", "Meet me at noon.", "Totally different thing entirely now."),
        ("d", "2026-10-01T13:00:00Z", "Call Max today.", "Call Max today."),
    ]

    def references(self):
        return {index + 1: left for index, (_, _, _, left) in enumerate(self.TAKES)}

    def test_a_candidate_that_reproduces_the_reference_scores_everything(self, tmp_path):
        from experiments import correction_replay as cr
        lines = self.build(tmp_path, self.TAKES)
        refs = self.references()
        candidate = cr.Candidate.of("oracle", "test", lambda audio, before, earlier: refs[int(audio[0] * 32768)])
        result = cr.score(lines, tmp_path / "takes", candidate, cache=tmp_path / "cache")
        assert [r.take.id for r in result.rows] == ["a", "b", "d"]
        assert all(r.exact and r.by_words for r in result.rows)
        assert [r.take.id for r in result.fixed] == ["b"]
        assert result.broke == []

    def test_a_candidate_that_reproduces_the_logged_text_fixes_and_breaks_nothing(self, tmp_path):
        from experiments import correction_replay as cr
        lines = self.build(tmp_path, self.TAKES)
        result = cr.score(lines, tmp_path / "takes", cr.logged_candidate(lines), cache=tmp_path / "cache")
        assert result.fixed == [] and result.broke == []
        assert [r.by_words for r in result.rows] == [True, False, True]

    def test_a_take_classed_gone_is_skipped(self, tmp_path):
        from experiments import correction_replay as cr
        lines = self.build(tmp_path, self.TAKES)
        assert "c" not in [t.id for t in cr.corpus(lines, tmp_path / "takes")]

    def test_a_candidate_is_never_handed_a_correction_from_a_later_take(self, tmp_path):
        from experiments import correction_replay as cr
        lines = self.build(tmp_path, self.TAKES)
        handed = {}

        def fn(audio, before, earlier):
            handed[int(audio[0] * 32768)] = [(c.before, c.after) for c in earlier]
            return ""

        cr.score(lines, tmp_path / "takes", cr.Candidate.of("spy", "test", fn), cache=tmp_path / "cache")
        assert handed[1] == []
        assert handed[2] == []
        assert handed[4] == [("phalanx", "failing")]

    def test_outputs_are_cached_per_candidate_and_model(self, tmp_path):
        from experiments import correction_replay as cr
        lines = self.build(tmp_path, self.TAKES)
        calls = []

        def fn(audio, before, earlier):
            calls.append(1)
            return "x"

        for model in ("m1", "m1", "m2"):
            cr.score(lines, tmp_path / "takes", cr.Candidate.of("c", model, fn), cache=tmp_path / "cache")
        assert len(calls) == 6


def test_field_reads_count_kinds_per_package_and_name_unusual_offsets():
    from experiments import phone_usage
    lines = [
        {"event": "start", "id": "a", "pkg": "ai.x.grok", "field_read": "offset_unknown", "field_offset": -1},
        {"event": "start", "id": "b", "pkg": "ai.x.grok", "field_read": "offset_unknown", "field_offset": -1},
        {"event": "start", "id": "c", "pkg": "com.android.chrome", "field_read": "full", "field_offset": 0},
        {"event": "start", "id": "d", "pkg": "com.android.chrome", "field_read": "partial", "field_offset": 7},
        {"event": "start", "id": "e", "pkg": "old"},
    ]
    text = "\n".join(phone_usage.field_reads(lines))
    assert "ai.x.grok: offset_unknown 2" in text
    assert "com.android.chrome: full 1, partial 1, other offsets {7: 1}" in text
    assert "old" not in text


def _grid_audio():
    from tests import make_vectors

    return make_vectors.to_float(make_vectors.pcm_signal(
        [["silence", 0.3], ["noise", 3, 8000], ["silence", 2.0], ["noise", 12, 8000]]))


class _Result:
    def __init__(self, tokens, timestamps):
        self.tokens, self.timestamps = tokens, timestamps


class _GridRecognizer:
    def __init__(self, text="alpha bravo"):
        self.text = text
        self.plain = 0
        self.context = []

    def recognize(self, samples):
        self.plain += 1
        return self.text

    def decode(self, samples):
        self.context.append(len(samples))
        words = self.text.split()
        return _Result([" " + word for word in words], [1e6] * len(words))


def _decode_counts(audio, cfg, pauses, hards):
    from stt_button.asr import Slicer, slice_bounds

    from experiments.boundary_punct import capped_search_s

    plain, context = set(), set()
    for start, end in slice_bounds(audio, cfg["slice_max_s"], cfg["slice_search_s"]):
        plain.add((start, end))
    for pause in pauses:
        for hard in hards:
            slicer = Slicer(hard, capped_search_s(hard, cfg["slice_search_s"]), pause, 2, 10)
            bounds = slicer.feed(audio) + slicer.finish()
            for n, (start, end) in enumerate(bounds):
                before = slicer.speech_before(start) if n else None
                after = slicer.speech_after(start) if n else None
                if n and before is not None and after is not None and after < end:
                    context.add((start, end, before, after))
                else:
                    plain.add((start, end))
    return plain, context


def _default_table(audio, metas, pauses, cfg):
    import statistics

    from experiments import wer
    from experiments.boundary_punct import boundary_counts
    from stt_button.asr import SAMPLE_RATE, join_slices, slice_bounds

    cache = {}
    stats = {arm: {"counts": dict.fromkeys(("breaks", "caps", "caps_after_j", "j_fixed", "j_harms"), 0),
                   "words": 0, "cuts": 0, "minutes": 0.0, "tails": [], "errors": 0, "ref": 0}
             for arm in pauses}

    def text_for(meta_id, bounds):
        texts = []
        for start, end in bounds:
            key = (meta_id, start, end)
            if key not in cache:
                cache[key] = "alpha bravo"
            texts.append(cache[key])
        return texts

    for meta in metas:
        reference = join_slices(text_for(meta["id"], slice_bounds(audio, cfg["slice_max_s"], cfg["slice_search_s"])))
        for arm in pauses:
            bounds = slice_bounds(audio, cfg["slice_max_s"], cfg["slice_search_s"],
                                  pause_s=arm, slice_min_s=2, pause_margin_db=10)
            slice_texts = text_for(meta["id"], bounds)
            slot = stats[arm]
            for name, value in boundary_counts(slice_texts, reference).items():
                slot["counts"][name] += value
            slot["words"] += len(reference.split())
            slot["cuts"] += len(bounds) - 1
            slot["minutes"] += len(audio) / SAMPLE_RATE / 60
            slot["tails"].append((bounds[-1][1] - bounds[-1][0]) / SAMPLE_RATE)
            if meta["mode"] == "read":
                ref = wer.normalize(meta["reference"])
                slot["errors"] += wer.word_errors(ref, wer.normalize(join_slices(slice_texts)))
                slot["ref"] += len(ref)
    lines = [
        "| pause_s | Read WER | False breaks /100 | False capitals /100 | Capitals after J /100 | J fixes | J harms | Cuts/min | Median last slice (s) |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for arm in pauses:
        slot, counts = stats[arm], stats[arm]["counts"]
        lines.append(
            f"| {arm} | {100 * slot['errors'] / slot['ref']:.2f}% | {100 * counts['breaks'] / slot['words']:.2f} | "
            f"{100 * counts['caps'] / slot['words']:.2f} | {100 * counts['caps_after_j'] / slot['words']:.2f} | "
            f"{counts['j_fixed']} | {counts['j_harms']} | {slot['cuts'] / slot['minutes']:.1f} | "
            f"{statistics.median(slot['tails']):.1f} |")
    return "\n".join(lines) + "\n"


class TestGridPick:
    def row(self, pause, hard, wer, breaks):
        return {"cut_pause": pause, "hard_cut": hard, "read_wer": wer, "false_breaks": breaks,
                "false_caps": 0.0, "cuts_per_min": 1.0, "median_slice_s": 4.0}

    def test_a_wer_on_the_bar_passes_and_a_wer_over_it_fails(self):
        from experiments.boundary_punct import pick
        on_bar = self.row(1.2, 15, 11.54, 0.0)
        over = self.row(0.5, 8, 11.55, 0.0)
        assert pick([over, on_bar]) == on_bar

    def test_false_breaks_on_the_bar_pass_and_over_it_fail(self):
        from experiments.boundary_punct import pick
        on_bar = self.row(1.2, 15, 0.0, 0.5)
        over = self.row(0.5, 8, 0.0, 0.51)
        assert pick([over, on_bar]) == on_bar

    def test_the_smallest_cut_pause_wins_and_then_the_smallest_hard_cut(self):
        from experiments.boundary_punct import pick
        assert pick([
            self.row(0.8, 8, 9.0, 0.0),
            self.row(0.6, 15, 10.0, 0.0),
            self.row(0.6, 10, 11.0, 0.0),
        ]) == self.row(0.6, 10, 11.0, 0.0)

    def test_no_passing_pair(self):
        from experiments.boundary_punct import pick
        assert pick([self.row(0.5, 8, 11.55, 0.0), self.row(1.2, 15, 10.0, 0.51)]) is None

    def test_the_table_names_the_pick_or_says_that_none_passes(self):
        from experiments.boundary_punct import format_grid
        chosen = self.row(0.6, 10, 11.0, 0.4)
        other = self.row(0.8, 8, 9.0, 0.0)
        assert format_grid([other, chosen]).splitlines()[-1] == "pick: cut_pause 0.6, hard_cut 10"
        assert format_grid([self.row(0.5, 8, 12.0, 0.0)]).splitlines()[-1] == "no pair passes"


class TestDecodeArm:
    def test_an_unmodified_arm_still_cuts_where_pause_1_6_and_the_config_slice_max_do(self):
        from stt_button import config
        from stt_button.asr import SAMPLE_RATE, Slicer

        from experiments.boundary_punct import decode_arm

        audio = _grid_audio()
        cfg = config.load()
        seen = []

        class Recognizer:
            def recognize(self, samples):
                seen.append(len(samples))
                return "update the plan."

            def decode(self, samples):
                raise AssertionError("the baseline arm decodes each slice on its own")

        text, decoded = decode_arm(Recognizer(), audio, cfg, None)
        slicer = Slicer.from_config({**cfg, "pause_s": 1.6})
        bounds = slicer.feed(audio) + slicer.finish()
        assert seen == [end - start for start, end in bounds]
        assert text == " ".join(["update the plan."] * len(bounds))
        assert decoded == len(audio) / SAMPLE_RATE

    def test_a_context_arm_still_replaces_the_previous_slice_mark_from_the_junction(self):
        from stt_button import config
        from stt_button.asr import Slicer

        from experiments.boundary_punct import decode_arm

        audio = _grid_audio()
        cfg = config.load()
        calls = {"plain": 0, "context": 0}

        class Recognizer:
            def recognize(self, samples):
                calls["plain"] += 1
                return "update the plan."

            def decode(self, samples):
                calls["context"] += 1
                return _Result([" plan", " have", " monitoring"], [0.0, 1e6, 1e6])

        text, _decoded = decode_arm(Recognizer(), audio, cfg, (4, 0.2))
        slicer = Slicer.from_config({**cfg, "pause_s": 1.6})
        n = len(slicer.feed(audio) + slicer.finish())
        assert n >= 2
        assert calls == {"plain": 1, "context": n - 1}
        assert text == " ".join(["update the plan"] + ["have monitoring"] * (n - 1))


class TestGrid:
    def test_every_pair_is_a_row_and_the_default_table_is_unchanged(self, monkeypatch, capsys):
        import sys

        from stt_button import config
        from stt_button.asr import Slicer, context_audio

        from experiments import boundary_punct as bp

        audio = _grid_audio()
        cfg = config.load()
        metas = [{"id": "read1", "mode": "read", "reference": "alpha bravo"}]
        pauses = [0.8, 1.2, 1.6]
        fake = _GridRecognizer()
        monkeypatch.setattr(bp, "Recognizer", lambda: fake)
        monkeypatch.setattr(bp.paths, "sidecars", lambda recordings=None: metas)
        monkeypatch.setattr(bp, "read_wav", lambda path: audio)

        monkeypatch.setattr(sys, "argv", ["boundary_punct"])
        assert bp.main() == 0
        assert capsys.readouterr().out == _default_table(audio, metas, pauses, cfg)
        assert fake.context == []

        fake.plain = 0
        monkeypatch.setattr(sys, "argv", ["boundary_punct", "--grid"])
        assert bp.main() == 0
        out = capsys.readouterr().out
        plain, context = _decode_counts(audio, cfg, bp.CUT_PAUSES, bp.HARD_CUTS)
        assert (fake.plain, len(fake.context)) == (len(plain), len(context))
        assert list(bp.CUT_PAUSES) == [0.5, 0.6, 0.8, 1.0, 1.2]
        assert list(bp.HARD_CUTS) == [8, 10, 15]
        for pause in bp.CUT_PAUSES:
            for hard in bp.HARD_CUTS:
                assert f"| {pause} | {hard} |" in out
        assert out.splitlines()[0] == ("| cut_pause | hard_cut | Read WER | False breaks /100 | False capitals /100 | "
                                       "Cuts/min | Median slice (s) |")
        assert out.splitlines()[-1] == "no pair passes"
        rows = bp.grid_rows(_GridRecognizer(), list(zip(metas, [audio])), cfg, bp.CUT_PAUSES, bp.HARD_CUTS)
        assert [(row["cut_pause"], row["hard_cut"]) for row in rows] == [
            (pause, hard) for pause in bp.CUT_PAUSES for hard in bp.HARD_CUTS]
        assert out == bp.format_grid(rows)
        assert all(row["cuts_per_min"] > 0 and row["median_slice_s"] > 0 for row in rows)

        slicer = Slicer(bp.HARD_CUTS[0], bp.capped_search_s(bp.HARD_CUTS[0], cfg["slice_search_s"]),
                        bp.CUT_PAUSES[0], 2, 10)
        bounds = slicer.feed(audio) + slicer.finish()
        start, end = bounds[1]
        phone, _junction = context_audio(audio, slicer.speech_before(start), slicer.speech_after(start), end, 4, 0.2)
        assert len(phone) in fake.context

    def test_a_ramble_is_left_out_of_read_wer(self):
        from stt_button import config

        from experiments.boundary_punct import CUT_PAUSES, HARD_CUTS, grid_rows

        audio = _grid_audio()
        cfg = config.load()
        read = {"id": "read1", "mode": "read", "reference": "alpha bravo"}
        ramble = {"id": "ram1", "mode": "ramble", "reference": "completely different words here"}
        one = grid_rows(_GridRecognizer(), [(read, audio)], cfg, CUT_PAUSES, HARD_CUTS)
        two = grid_rows(_GridRecognizer(), [(read, audio), (ramble, audio)], cfg, CUT_PAUSES, HARD_CUTS)
        assert [row["read_wer"] for row in one] == [row["read_wer"] for row in two]
        assert one[0]["read_wer"] > 11.54

    def test_the_search_window_never_exceeds_the_hard_cut_minus_one_second(self):
        import statistics

        from stt_button import config
        from stt_button.asr import CUT_WINDOW_S, SAMPLE_RATE, Slicer

        from experiments.boundary_punct import capped_search_s, grid_rows

        assert capped_search_s(8, 5) == 5
        assert capped_search_s(15, 5) == 5
        assert capped_search_s(8, 100) == 7
        assert capped_search_s(1.0, 5) == CUT_WINDOW_S
        assert capped_search_s(0.05, 5) == 0.05

        audio = _grid_audio()
        cfg = {**config.load(), "slice_search_s": 100}
        meta = {"id": "read1", "mode": "read", "reference": "alpha bravo"}
        rows = grid_rows(_GridRecognizer(), [(meta, audio)], cfg, [0.5], [8])
        slicer = Slicer(8, 7, 0.5, 2, 10)
        bounds = slicer.feed(audio) + slicer.finish()
        lengths = [(end - start) / SAMPLE_RATE for start, end in bounds]
        assert rows[0]["median_slice_s"] == statistics.median(lengths)
