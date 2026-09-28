import re

NUMBER_WORDS = frozenset(
    "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen "
    "sixteen seventeen eighteen nineteen twenty thirty forty fifty sixty seventy eighty ninety "
    "hundred thousand million first second third fourth fifth sixth seventh eighth ninth tenth "
    "eleventh twelfth fourteenth twentieth thirtieth".split()
)


def normalize(text: str) -> list[str]:
    text = text.lower().replace("-", " ")
    text = re.sub(r"[^a-z0-9' ]+", " ", text)
    return [w.strip("'") for w in text.split() if w.strip("'")]


def without_numbers(tokens: list[str]) -> list[str]:
    """Drop digit tokens and spelled-out number words.

    The read references spell numbers as words and a recognizer may write digits; comparing the
    rest measures recognition rather than number formatting.
    """
    return [t for t in tokens if t not in NUMBER_WORDS and not any(c.isdigit() for c in t)]


def word_errors(reference: list[str], hypothesis: list[str]) -> int:
    """Levenshtein distance over words: substitutions + insertions + deletions."""
    previous = list(range(len(hypothesis) + 1))
    for i, ref_word in enumerate(reference, 1):
        current = [i]
        for j, hyp_word in enumerate(hypothesis, 1):
            current.append(min(
                previous[j] + 1,
                current[j - 1] + 1,
                previous[j - 1] + (ref_word != hyp_word),
            ))
        previous = current
    return previous[-1]
