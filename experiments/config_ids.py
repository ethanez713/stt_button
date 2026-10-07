"""Which configuration each logged take ran under, and the refusal to pool takes of different ones."""

from collections import Counter

UNKNOWN = "unknown"


def counts(lines: list[dict]) -> Counter:
    """Takes per `config_id` of their `start` line; a take logged before the field existed counts as `unknown`."""
    return Counter(line.get("config_id") or UNKNOWN for line in lines if line.get("event") == "start")


def check(lines: list[dict], all_configs: bool) -> tuple[str, bool]:
    """The line naming each id and its takes, and whether the report may go on: not with two ids unless `all_configs`."""
    found = counts(lines)
    text = "config_id: " + ", ".join(f"{name} {n} takes" for name, n in sorted(found.items()))
    if len(found) > 1 and not all_configs:
        return text + "\nThe lines carry more than one configuration; pass --all-configs to pool them.", False
    return text, True
