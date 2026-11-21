from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest

from util import decode_cursor, encode_cursor, hash_password, parse_content_range, verify_password


def test_password_roundtrip():
    h = hash_password("correct horse")
    assert verify_password("correct horse", h)
    assert not verify_password("wrong", h)
    assert not verify_password("x", "garbage")


def test_cursor_roundtrip_with_positive_offset():
    ts, mid = datetime(2024, 7, 14, 21, 30, tzinfo=timezone(timedelta(hours=2))), uuid4()
    cur = encode_cursor(ts, mid)
    assert "+" not in cur and "/" not in cur
    assert decode_cursor(cur) == (ts, mid)
    with pytest.raises(ValueError):
        decode_cursor("bm9wZQ==")  # "nope"


def test_content_range():
    assert parse_content_range("bytes 0-99/1000", 100) == (0, 99, 1000)
    assert parse_content_range("bytes 900-999/1000", 100) == (900, 999, 1000)
    for bad in ("bytes 0-100/1000", "bytes 10-5/1000", "bytes 0-1000/1000", "0-9/10", "bytes */10"):
        with pytest.raises(ValueError):
            parse_content_range(bad, 100)


def test_map_cell_halves_with_each_zoom():
    from util import map_cell
    assert map_cell(0) == 1 / 4
    assert map_cell(3) == map_cell(2) / 2


def test_bbox_condition_handles_antimeridian():
    from util import bbox_condition
    sql = bbox_condition("x")
    assert "x.latitude BETWEEN %(south)s AND %(north)s" in sql
    assert "x.longitude >= %(west)s OR x.longitude <= %(east)s" in sql
