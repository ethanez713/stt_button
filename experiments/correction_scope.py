"""Does the cleaner resolve a self-correction better alone with its statement, or inside a chunk?

    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.correction_scope

Each case is a statement with a second clause and a correction that opens the next sentence. It is
cleaned as the pair alone (what a correction unit gives the cleaner), and after 120 to 320 tokens of
a ramble transcript in one chunk. A case resolves when the corrected value is in the output and the
superseded one is not.
"""

import re
import sys

from experiments import paths
from stt_button.cleaner import Cleaner, split_sentences

CASES = [
 ("For dinner, I booked a table at the Hotel Adler on Tuesday evening, and the reservation is under my name.", "Actually, no, make that Wednesday at the Hotel Birch.", ["birch"], ["adler", "tuesday"]),
 ("The meeting is at three in the main conference room, and Dana is bringing the slides.", "Sorry, I meant four.", ["four", "4", "4:00"], ["three", "3", "3:00"]),
 ("Send the report to Marcus by Friday so he can review it before the board call.", "Actually, make that Thursday.", ["thursday"], ["friday"]),
 ("We need twelve chairs for the back room, and the caterer wants a headcount by noon.", "No wait, fifteen chairs.", ["fifteen", "15"], ["twelve", "12"]),
 ("I'll pick you up at the north entrance around six, and we can drive straight to the venue.", "Scratch that, the south entrance.", ["south"], ["north"]),
 ("The budget for the first phase is forty thousand dollars, which covers the contractor and the permits.", "I mean fifty thousand.", ["50,000", "fifty"], ["40,000", "forty"]),
 ("Tell Priya the flight lands at gate B twelve, and she should wait by the baggage claim.", "Actually it's gate C four.", ["c4", "c 4", "c-4", "c four"], ["b12", "b 12", "b-12", "b twelve"]),
 ("Install the build on the S20 Ultra first, and then run the smoke test before lunch.", "No, not the S20, the S25 Ultra.", ["s25"], ["s20"]),
 ("Let's use the blue template for the newsletter, since the client liked it last time.", "Or rather the green one.", ["green"], ["blue"]),
 ("The deadline for the draft is March third, and the final version is due two weeks after that.", "Wait, no, March tenth.", ["march 10", "tenth"], ["march 3", "third"]),
 ("Order two large pizzas for the team, one with mushrooms and one plain.", "Actually make it three.", ["three", "3"], ["two large", "2 large"]),
 ("My new address is 48 Elm Street, apartment five, right above the bakery.", "Correction, 84 Elm Street.", ["84"], ["48"]),
 ("For dinner, I booked a table at Hotel Adler on Tuesday morning, and the reservations under my name.", "Actually, no, make that Wednesday at the Hotel Birch.", ["birch"], ["adler", "tuesday"]),
 ("So the GPU is what we should target for the first release because it is faster on this laptop.", "Uh, or CPU rather.", ["cpu"], ["gpu"]),
 ("Can you ask Jordan to move the standup to nine thirty on Mondays since half the team is remote.", "Sorry, ten thirty.", ["10:30", "ten thirty"], ["9:30", "nine thirty"]),
 ("I think we should ship version two point one this week and leave the redesign for later.", "Actually no, two point two.", ["2.2"], ["2.1"]),
 ("The parking garage is on Fifth Avenue next to the pharmacy and it closes at midnight.", "Wait, Sixth Avenue.", ["sixth", "6th"], ["fifth", "5th"]),
 ("Put the old laptops in the storage room on the second floor and label each box.", "I meant the third floor.", ["third", "3rd"], ["second", "2nd"]),
 ("We should invite about thirty people to the launch so the room does not feel empty.", "No, make it forty.", ["forty", "40"], ["thirty", "30"]),
 ("Remind me to call the dentist on Monday about the cleaning and ask about the invoice.", "Never mind, on Tuesday.", ["tuesday"], ["monday"]),
 ("The password reset link should expire after ten minutes because that is what the policy says.", "Oops, fifteen minutes.", ["fifteen", "15"], ["ten minutes", "10 minutes"]),
 ("Book the rental car from the airport for four days and add the extra insurance.", "Actually make that five days.", ["five", "5"], ["four", "4"]),
 ("Dinner is at seven at my place and bring something to drink, I mean eight, not seven.", "See you then.", ["eight", "8"], ["at seven", "at 7"]),
 ("The client wants the logo in red on the cover page and smaller on the inside pages.", "Scratch that, in navy.", ["navy"], ["red"]),
]

LEADS = {"ramble 130": (1, 120), "ramble 320": (2, 250), "ramble 160": (4, 150)}


def has(text: str, word: str) -> bool:
    return re.search(rf"(?<!\w){re.escape(word)}(?!\w)", text.lower()) is not None


def resolved(text: str, wanted: list[str], unwanted: list[str]) -> bool:
    return any(has(text, w) for w in wanted) and not any(has(text, u) for u in unwanted)


def lead(cleaner: Cleaner, index: int, tokens: int) -> str:
    sentences: list[str] = []
    files = sorted((paths.EXPERIMENTS / "asr" / "unified").glob("m*.txt"))
    for sentence in split_sentences(files[index].read_text()):
        sentences.append(sentence)
        if cleaner.count_tokens(" ".join(sentences)) >= tokens:
            break
    return " ".join(sentences)


def main() -> int:
    cleaner = Cleaner(backend="ollama")
    arms = {"pair alone": ""} | {name: lead(cleaner, *spec) for name, spec in LEADS.items()}
    for arm, before in arms.items():
        hits = 0
        for statement, correction, wanted, unwanted in CASES:
            output = cleaner.clean_chunk(f"{before} {statement} {correction}".strip())[0]
            tail = output[len(output) // 3:] if before else output
            ok = resolved(tail, wanted, unwanted)
            hits += ok
            if not ok:
                print(f"  MISS {arm}: {output[-140:]!r}")
        print(f"{arm}: {hits} of {len(CASES)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
