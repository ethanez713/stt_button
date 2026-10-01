import json

from tests import make_vectors


def test_vectors_file_matches_the_generator():
    assert json.loads(make_vectors.FIXTURE.read_text()) == make_vectors.vectors()
