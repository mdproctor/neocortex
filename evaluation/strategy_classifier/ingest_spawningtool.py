"""Ingest Spawning Tool replay packs: parse .SC2Replay, extract build orders, label, save.

Uses the `spawningtool` Python library (v3.0.0) to parse raw replay files.
Build orders are normalized to the common format for the unified labelling
pipeline.

Usage: python3 -m evaluation.strategy_classifier.ingest_spawningtool --dir <replay_dir>
"""
import numpy as np
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from evaluation.strategy_classifier.config import (
    MATCHUPS, HyperParams, Paths, archetypes_for_matchup,
)
from evaluation.strategy_classifier.sc2egset_extractor import BUILDINGS, UNITS
from evaluation.strategy_classifier.labelling.label_pipeline import label_replay
from evaluation.strategy_classifier.dataset import per_replay_split

_BUILDINGS_SET = frozenset(BUILDINGS)
_UNITS_SET = frozenset(UNITS)
_ALL_TRACKABLE = _BUILDINGS_SET | _UNITS_SET

MATCHUP_MAP = {
    ("Terran", "Terran"): "vs_terran",
    ("Terran", "Zerg"): "vs_zerg",
    ("Terran", "Protoss"): "vs_protoss",
    ("Zerg", "Terran"): "vs_terran",
    ("Zerg", "Zerg"): "vs_zerg",
    ("Zerg", "Protoss"): "vs_protoss",
    ("Protoss", "Terran"): "vs_terran",
    ("Protoss", "Zerg"): "vs_zerg",
    ("Protoss", "Protoss"): "vs_protoss",
}


def _parse_time(time_str: str) -> float:
    parts = time_str.split(":")
    if len(parts) == 2:
        return int(parts[0]) + int(parts[1]) / 60.0
    return 0.0


def extract_spawningtool_build_order(parsed: dict, player_id: str) -> List[Dict]:
    """Convert spawningtool parsed replay to common build-order format.

    Filters out workers. Classifies each entry as 'building' or 'unit'
    based on the canonical BUILDINGS/UNITS lists.
    """
    player = parsed.get("players", {}).get(player_id, {})
    raw_orders = player.get("buildOrder", [])

    build_order = []
    for entry in raw_orders:
        if entry.get("is_worker", False):
            continue

        name = entry.get("name", "")
        if name not in _ALL_TRACKABLE:
            continue

        minute = _parse_time(entry.get("time", "0:00"))
        entry_type = "building" if name in _BUILDINGS_SET else "unit"
        build_order.append({
            "type": entry_type,
            "name": name,
            "minute": minute,
        })

    return sorted(build_order, key=lambda x: x["minute"])


def parse_and_label_replays(
    replay_dir: Path,
    llm_client=None,
) -> Dict[str, List[Tuple[List[Dict], str]]]:
    """Parse all .SC2Replay files in a directory, extract build orders, label.

    Returns dict[matchup] -> list of (build_order, label).
    """
    from spawningtool.parser import parse_replay

    labelled: Dict[str, list] = {m: [] for m in MATCHUPS}
    stats = {"rule": 0, "llm": 0, "excluded": 0, "total": 0, "parse_error": 0}

    replay_files = list(replay_dir.rglob("*.SC2Replay"))
    print(f"  Found {len(replay_files)} replay files")

    for replay_path in replay_files:
        try:
            parsed = parse_replay(str(replay_path))
        except Exception:
            stats["parse_error"] += 1
            continue

        players = parsed.get("players", {})
        if len(players) != 2:
            continue

        player_ids = sorted(players.keys())
        for observer_id, opponent_id in [(player_ids[0], player_ids[1]),
                                          (player_ids[1], player_ids[0])]:
            observer_race = players[observer_id].get("race", "")
            opponent_race = players[opponent_id].get("race", "")
            matchup_key = (observer_race, opponent_race)
            if matchup_key not in MATCHUP_MAP:
                continue
            matchup = MATCHUP_MAP[matchup_key]

            opponent_build = extract_spawningtool_build_order(parsed, opponent_id)
            label, source = label_replay(opponent_build, opponent_race, llm_client)

            stats["total"] += 1
            stats[source] += 1

            if label is not None:
                labelled[matchup].append((opponent_build, label))

    print(f"  Labelling stats: {stats['total']} total, "
          f"{stats['rule']} rule-based, {stats['llm']} LLM, "
          f"{stats['excluded']} excluded, {stats['parse_error']} parse errors")
    for m in MATCHUPS:
        print(f"    {m}: {len(labelled[m])} labelled")

    return labelled


def _save_split(samples, path: Path):
    if not samples:
        return
    temporal = np.array([s[0] for s in samples])
    map_feat = np.array([s[1] for s in samples])
    labels = np.array([s[2] for s in samples])
    np.savez_compressed(path, temporal=temporal, map_features=map_feat, labels=labels)


def ingest_spawningtool(
    replay_dir: Path,
    hp: HyperParams = HyperParams(),
    paths: Paths = Paths(),
    seed: int = 42,
):
    """Full ingestion: parse replays, label, build features, save .npz splits."""
    from evaluation.strategy_classifier.feature_engineering import (
        build_temporal_features, build_map_tensor,
    )
    from evaluation.strategy_classifier.fog_of_war import generate_scouting_mask

    rng = np.random.default_rng(seed)
    output = paths.data / "spawningtool"
    output.mkdir(parents=True, exist_ok=True)

    print("Step 1: Parsing and labelling replays...")
    labelled = parse_and_label_replays(replay_dir)

    print("\nStep 2: Building features and saving...")
    minutes = [2, 3, 4, 5]

    for matchup in MATCHUPS:
        archetypes = archetypes_for_matchup(matchup)
        arch_to_idx = {a: i for i, a in enumerate(archetypes)}
        entries = labelled.get(matchup, [])

        if not entries:
            print(f"  {matchup}: no labelled replays, skipping")
            continue

        samples = []
        replay_ids = []
        replay_labels = []

        for replay_id, (build_order, label) in enumerate(entries):
            if label not in arch_to_idx:
                continue
            label_idx = arch_to_idx[label]

            n_features = 239
            duration = 300
            own_feat = np.zeros((duration, n_features // 2), dtype=np.float32)
            opp_feat = np.zeros((duration, n_features // 2), dtype=np.float32)

            map_tensor = build_map_tensor("Unknown")
            mask = generate_scouting_mask(duration, rng)

            has_any = False
            for minute in minutes:
                if minute * 60 > duration:
                    continue
                temporal = build_temporal_features(own_feat, opp_feat, mask, minute, hp)
                samples.append((temporal, map_tensor, label_idx))
                has_any = True

            if has_any:
                replay_ids.append(replay_id)
                replay_labels.append(label_idx)

        if not samples:
            print(f"  {matchup}: no valid samples")
            continue

        train_ids, val_ids, test_ids = per_replay_split(
            replay_ids, replay_labels, seed=seed
        )
        train_set, val_set = set(train_ids), set(val_ids)

        train_samples, val_samples, test_samples = [], [], []
        idx = 0
        for rid in replay_ids:
            count = 0
            while idx < len(samples) and count < len(minutes):
                if rid in train_set:
                    train_samples.append(samples[idx])
                elif rid in val_set:
                    val_samples.append(samples[idx])
                else:
                    test_samples.append(samples[idx])
                idx += 1
                count += 1

        matchup_dir = output / matchup
        matchup_dir.mkdir(parents=True, exist_ok=True)
        _save_split(train_samples, matchup_dir / "train.npz")
        _save_split(val_samples, matchup_dir / "val.npz")
        _save_split(test_samples, matchup_dir / "test.npz")

        print(f"  {matchup}: {len(train_samples)} train, {len(val_samples)} val, "
              f"{len(test_samples)} test ({len(archetypes)} classes)")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir", required=True, type=Path,
                        help="Directory containing .SC2Replay files")
    parser.add_argument("--llm", action="store_true")
    args = parser.parse_args()
    ingest_spawningtool(args.dir)
