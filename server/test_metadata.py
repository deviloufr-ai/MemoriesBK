from datetime import datetime, timezone

from pathlib import Path

import pytest

from metadata import extract_fields, media_mime, safe_join, storage_rel_path, visible_dir


def test_safe_join_stays_under_root(tmp_path):
    (tmp_path / "a" / "b").mkdir(parents=True)
    assert safe_join(tmp_path, "") == tmp_path.resolve()
    assert safe_join(tmp_path, "/a/b") == (tmp_path / "a" / "b").resolve()
    for bad in ("..", "a/../../etc", "../" * 5 + "etc"):
        with pytest.raises(ValueError):
            safe_join(tmp_path, bad)


def test_media_filters():
    assert media_mime("IMG_1.HEIC") == "image/heic" and media_mime("v.MOV") == "video/quicktime"
    assert media_mime("notes.txt") is None and media_mime(".hidden.jpg") is None and media_mime("jpg") is None
    assert visible_dir("Japon", {"photos-app"}) and not visible_dir("@eaDir", set())
    assert not visible_dir("photos-app", {"photos-app"}) and not visible_dir(".quarantine", set())
    assert isinstance(safe_join(Path("."), "x"), Path)


def test_photo_portrait_with_gps():
    f = extract_fields({
        "DateTimeOriginal": "2024:07:14 21:30:05+02:00", "Orientation": 6,
        "ImageWidth": 4000, "ImageHeight": 3000, "Make": "Google", "Model": "Pixel 8",
        "GPSLatitude": 48.85, "GPSLatitudeRef": "N", "GPSLongitude": 2.35, "GPSLongitudeRef": "E",
    })
    assert f["taken_at"] == datetime(2024, 7, 14, 21, 30, 5, tzinfo=timezone.utc)
    assert (f["width"], f["height"]) == (3000, 4000)
    assert (f["latitude"], f["longitude"]) == (48.85, 2.35)
    assert f["camera_model"] == "Pixel 8"


def test_fallback_date_unsigned_southern_gps_video():
    f = extract_fields({
        "DateTimeOriginal": "0000:00:00 00:00:00", "CreateDate": "2023:01:02 03:04:05",
        "GPSLatitude": 33.9, "GPSLatitudeRef": "S", "GPSLongitude": -151.2, "GPSLongitudeRef": "W",
        "Duration": 12.5, "ImageWidth": 1920, "ImageHeight": 1080, "Rotation": 90,
    })
    assert f["taken_at"].year == 2023
    assert (f["latitude"], f["longitude"]) == (-33.9, -151.2)  # pas de double inversion
    assert f["duration_ms"] == 12500
    assert (f["width"], f["height"]) == (1080, 1920)


def test_missing_or_null_island_gps():
    assert extract_fields({"GPSLatitude": 0, "GPSLongitude": 0})["latitude"] is None
    assert extract_fields({})["taken_at"] is None


def test_storage_path_sanitizes_client_name():
    d = datetime(2024, 1, 2, tzinfo=timezone.utc)
    assert storage_rel_path("u1", d, "ab" * 32, "IMG_001.JPG") == f"u1/2024/01/02/{'ab' * 32}.jpg"
    assert storage_rel_path("u1", d, "cd", "../../etc/passwd").endswith("/cd.bin")
    assert storage_rel_path("u1", d, "cd", "x.ph p").endswith("/cd.bin")


def test_date_from_name():
    from datetime import datetime, timezone

    from metadata import date_from_name, parse_exif_date
    utc = timezone.utc
    assert date_from_name("20170303_233116.jpg") == datetime(2017, 3, 3, 23, 31, 16, tzinfo=utc)
    assert date_from_name("IMG_20201224_152809.jpg") == datetime(2020, 12, 24, 15, 28, 9, tzinfo=utc)
    assert date_from_name("VID-20260825-WA0000.mp4") == datetime(2026, 8, 25, tzinfo=utc)
    assert date_from_name("Screenshot_20240101-101010.png") == datetime(2024, 1, 1, 10, 10, 10, tzinfo=utc)
    assert date_from_name("IMG_1234.jpg") is None
    assert date_from_name("video-2011-09-07-22-11-25.mp4") == datetime(2011, 9, 7, 22, 11, 25, tzinfo=utc)
    assert date_from_name("PXL_20230101_120000123.jpg") == datetime(2023, 1, 1, tzinfo=utc)
    assert date_from_name("20230231_000000.jpg") is None  # 31 février
    assert date_from_name("0234567890123.jpg") is None  # 13 chiffres hors plage des horodatages
    assert date_from_name("FB_IMG_1776982127173.jpg").date().isoformat() == "2026-04-23"
    assert date_from_name("1767428388314.jpg").date().isoformat() == "2026-01-03"
    assert date_from_name("motion_photo_3105642282487461914.jpg") is None
    # dates vidéo absurdes (QuickTime 1904, décalage de 66 ans) ignorées
    assert parse_exif_date({"CreateDate": "1904:01:01 00:00:00"}) is None
    assert parse_exif_date({"CreateDate": "1946:04:25 15:48:39"}) is None
    assert parse_exif_date({"DateTimeOriginal": "1946:04:25 15:48:39", "CreateDate": "2012:04:25 16:45:38"}).year == 2012
