import json

from experiments import report_asr, report_clean, vote


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
