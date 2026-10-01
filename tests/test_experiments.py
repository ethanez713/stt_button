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

    def test_cues_and_edited_inserts_are_counted(self):
        from experiments import phone_usage
        lines = [self.insert(1, cue="actually"), self.insert(2, cue="actually"), self.insert(3, cue="make it"),
                 {"event": "edit", "id": "t1", "text_after": "text 1 and more"},
                 {"event": "edit", "id": "t2", "text_after": "something else"}]
        report = phone_usage.summarize(lines)
        assert "cue matches: actually 2, make it 1" in report
        assert "edited afterwards: 1 of 2" in report
