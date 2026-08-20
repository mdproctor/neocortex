from evaluation.strategy_classifier.ingest_spawningtool import (
    extract_spawningtool_build_order, _parse_time,
)


def test_parse_time_minutes_seconds():
    assert _parse_time("0:00") == 0.0
    assert _parse_time("1:00") == 1.0
    assert abs(_parse_time("0:54") - 0.9) < 0.01
    assert abs(_parse_time("3:20") - (3 + 20/60)) < 0.01


def test_extract_build_order_from_parsed_replay():
    parsed = {
        "players": {
            "1": {
                "race": "Zerg",
                "buildOrder": [
                    {"name": "Hatchery", "time": "0:00", "supply": 12,
                     "is_worker": False, "frame": 0, "clock_position": None,
                     "is_chronoboosted": False},
                    {"name": "Drone", "time": "0:12", "supply": 13,
                     "is_worker": True, "frame": 269, "clock_position": None,
                     "is_chronoboosted": False},
                    {"name": "SpawningPool", "time": "0:54", "supply": 14,
                     "is_worker": False, "frame": 1210, "clock_position": None,
                     "is_chronoboosted": False},
                    {"name": "RoachWarren", "time": "3:20", "supply": 28,
                     "is_worker": False, "frame": 4480, "clock_position": None,
                     "is_chronoboosted": False},
                ],
            },
        },
    }
    build_order = extract_spawningtool_build_order(parsed, player_id="1")

    buildings = [e for e in build_order if e["type"] == "building"]
    assert len(buildings) == 3
    assert buildings[0]["name"] == "Hatchery"
    assert buildings[1]["name"] == "SpawningPool"
    assert buildings[2]["name"] == "RoachWarren"
    assert abs(buildings[1]["minute"] - 0.9) < 0.01

    workers = [e for e in build_order if e["name"] == "Drone"]
    assert len(workers) == 0


def test_extract_build_order_skips_workers():
    parsed = {
        "players": {
            "1": {
                "race": "Terran",
                "buildOrder": [
                    {"name": "SCV", "time": "0:12", "supply": 13,
                     "is_worker": True, "frame": 269, "clock_position": None,
                     "is_chronoboosted": False},
                ],
            },
        },
    }
    build_order = extract_spawningtool_build_order(parsed, player_id="1")
    assert len(build_order) == 0


def test_extract_build_order_includes_units():
    parsed = {
        "players": {
            "1": {
                "race": "Terran",
                "buildOrder": [
                    {"name": "Barracks", "time": "1:00", "supply": 15,
                     "is_worker": False, "frame": 1344, "clock_position": None,
                     "is_chronoboosted": False},
                    {"name": "Marine", "time": "2:00", "supply": 18,
                     "is_worker": False, "frame": 2688, "clock_position": None,
                     "is_chronoboosted": False},
                ],
            },
        },
    }
    build_order = extract_spawningtool_build_order(parsed, player_id="1")
    assert len(build_order) == 2
    assert build_order[0]["type"] == "building"
    assert build_order[0]["name"] == "Barracks"
    assert build_order[1]["type"] == "unit"
    assert build_order[1]["name"] == "Marine"


def test_extract_build_order_empty_player():
    parsed = {"players": {"1": {"race": "Protoss", "buildOrder": []}}}
    assert extract_spawningtool_build_order(parsed, "1") == []


def test_extract_build_order_missing_player():
    parsed = {"players": {}}
    assert extract_spawningtool_build_order(parsed, "1") == []
