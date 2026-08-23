"""Ingest MSC dataset: extract build orders from sparse feature matrices, label, save.

The MSC (Macro Strategy Classification) dataset stores per-timestep game state as
sparse CSC matrices. Each file represents one player's perspective. The feature
layout is: [reward(1), action(1), score_cumulative(13), scalars(12), alerts,
upgrades, research, friendly_units(N*6), enemy_units(M*6)].

Only directories where the stat file can encode the player's unit types are usable.
The Zerg stat has all 119 unit types (all races); Terran/Protoss stats only have
their own race. So cross-race matchups only work through the Zerg-stat directories.

Usage: python3 -m evaluation.strategy_classifier.ingest_msc
"""
import json
import numpy as np
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from scipy.sparse import csc_matrix
from evaluation.strategy_classifier.config import (
    MATCHUPS, HyperParams, Paths, archetypes_for_matchup,
)
from evaluation.strategy_classifier.sc2egset_extractor import (
    BUILDINGS, UNITS, STAT_KEYS, BUILDING_IDX, UNIT_IDX,
    N_BUILDINGS, N_UNITS, N_STATS, N_FEATURES_PER_PLAYER,
)
from evaluation.strategy_classifier.labelling.label_pipeline import label_replay
from evaluation.strategy_classifier.feature_engineering import (
    build_temporal_features, build_map_tensor,
)
from evaluation.strategy_classifier.fog_of_war import generate_scouting_mask
from evaluation.strategy_classifier.dataset import per_replay_split

LOOPS_PER_SECOND = 22.4

MSC_SOURCES = [
    # (matchup_dir, subdir, stat_race, player_race, our_matchup)
    ("Terran_vs_Zerg", "Zerg", "Zerg", "Terran", "vs_terran"),
    ("Terran_vs_Terran", "Terran", "Terran", "Terran", "vs_terran"),
    ("Protoss_vs_Zerg", "Zerg", "Zerg", "Protoss", "vs_protoss"),
    ("Protoss_vs_Protoss", "Protoss", "Protoss", "Protoss", "vs_protoss"),
    ("Zerg_vs_Zerg", "Zerg", "Zerg", "Zerg", "vs_zerg"),
]

_BUILDINGS_SET = frozenset(BUILDINGS)
_UNITS_SET = frozenset(UNITS)

MSC_SCALAR_MAP = {
    16: ("scoreValueMineralsCurrent", "max_minerals"),
    17: ("scoreValueVespeneCurrent", "max_vespene"),
    18: ("scoreValueFoodMade", "max_food_cap"),
    19: ("scoreValueFoodUsed", "max_food_cap"),
    21: ("scoreValueWorkersActiveCount", "max_food_cap"),
}


def _load_stat(path: Path) -> dict:
    with open(path) as f:
        stat = json.load(f)
    result = {}
    for k, v in stat.items():
        if isinstance(v, dict):
            converted = {}
            for kk, vv in v.items():
                try:
                    key = int(kk)
                except ValueError:
                    key = kk
                try:
                    val = int(vv)
                except (ValueError, TypeError):
                    val = vv
                converted[key] = val
            result[k] = converted
        else:
            try:
                result[k] = int(v)
            except (ValueError, TypeError):
                result[k] = v
    return result


def _compute_offsets(stat: dict) -> Tuple[int, int, int]:
    """Return (units_offset, n_unit_slots, max_unit_num)."""
    n_alert = len(stat.get("alert", {}))
    n_upgrades = len(stat.get("upgrades", {}))
    n_research = len(stat.get("research_id", {}))
    n_unit_slots = max(stat["units_type"].values()) + 1
    units_offset = 15 + 12 + n_alert + n_upgrades + n_research
    return units_offset, n_unit_slots, stat["max_unit_num"]


def _build_unit_name_map(stat: dict) -> Dict[int, str]:
    """Map encoded unit index → unit name."""
    idx_to_name = {}
    for uid, encoded_idx in stat["units_type"].items():
        name = stat["units_name"].get(uid, None)
        if name:
            idx_to_name[encoded_idx] = name
    return idx_to_name


def _extract_build_order(
    dense: np.ndarray,
    stat: dict,
    units_offset: int,
    n_unit_slots: int,
    max_unit_num: int,
) -> List[Dict]:
    """Extract build order from unit count transitions in the feature matrix."""
    idx_to_name = _build_unit_name_map(stat)
    max_frame_id = stat.get("max_frame_id", 1)

    build_order = []
    prev_counts = np.zeros(n_unit_slots, dtype=np.int32)

    for t in range(dense.shape[0]):
        frame_id = dense[t, 15] * max_frame_id
        minute = frame_id / (LOOPS_PER_SECOND * 60)

        for ui in range(n_unit_slots):
            feat_idx = units_offset + ui * 6 + 1
            if feat_idx >= dense.shape[1]:
                break
            raw = dense[t, feat_idx]
            count = int(round(raw * max_unit_num))

            if count > prev_counts[ui]:
                name = idx_to_name.get(ui)
                if name and name in _BUILDINGS_SET:
                    for _ in range(count - prev_counts[ui]):
                        build_order.append({
                            "type": "building", "name": name, "minute": minute,
                        })
                elif name and name in _UNITS_SET:
                    for _ in range(count - prev_counts[ui]):
                        build_order.append({
                            "type": "unit", "name": name, "minute": minute,
                        })
            prev_counts[ui] = count

    return sorted(build_order, key=lambda x: x["minute"])


def _extract_per_second_features(
    dense: np.ndarray,
    stat: dict,
    units_offset: int,
    n_unit_slots: int,
    max_unit_num: int,
    max_seconds: int = 600,
) -> np.ndarray:
    """Convert MSC sparse features to per-second feature arrays in our 119-dim format."""
    idx_to_name = _build_unit_name_map(stat)
    max_frame_id = stat.get("max_frame_id", 1)

    seconds_list = []
    for t in range(dense.shape[0]):
        sec = dense[t, 15] * max_frame_id / LOOPS_PER_SECOND
        seconds_list.append(sec)

    if not seconds_list:
        return np.zeros((1, N_FEATURES_PER_PLAYER), dtype=np.float32)

    duration = min(int(seconds_list[-1]) + 1, max_seconds)
    if duration < 1:
        duration = 1

    features = np.zeros((duration, N_FEATURES_PER_PLAYER), dtype=np.float32)

    building_counts = np.zeros(N_BUILDINGS, dtype=np.float32)
    unit_counts = np.zeros(N_UNITS, dtype=np.float32)
    stat_values = np.zeros(N_STATS, dtype=np.float32)

    t_idx = 0
    for sec in range(duration):
        while t_idx < len(seconds_list) - 1 and seconds_list[t_idx + 1] <= sec:
            t_idx += 1

        if t_idx < dense.shape[0]:
            for ui in range(n_unit_slots):
                feat_idx = units_offset + ui * 6 + 1
                if feat_idx >= dense.shape[1]:
                    break
                name = idx_to_name.get(ui)
                if not name:
                    continue
                count = dense[t_idx, feat_idx] * max_unit_num
                if name in BUILDING_IDX:
                    building_counts[BUILDING_IDX[name]] = count
                elif name in UNIT_IDX:
                    unit_counts[UNIT_IDX[name]] = count

            for msc_idx, (stat_key, max_key) in MSC_SCALAR_MAP.items():
                if stat_key in STAT_KEYS:
                    si = STAT_KEYS.index(stat_key)
                    max_val = stat.get(max_key, 1)
                    stat_values[si] = dense[t_idx, msc_idx] * max_val / 1000.0

        features[sec, :N_BUILDINGS] = building_counts
        features[sec, N_BUILDINGS:N_BUILDINGS + N_UNITS] = unit_counts
        features[sec, N_BUILDINGS + N_UNITS:N_BUILDINGS + N_UNITS + N_STATS] = stat_values

    return features


def _save_split(samples, path: Path):
    if not samples:
        return
    temporal = np.array([s[0] for s in samples])
    map_feat = np.array([s[1] for s in samples])
    labels = np.array([s[2] for s in samples])
    np.savez_compressed(path, temporal=temporal, map_features=map_feat, labels=labels)


def ingest_msc(
    hp: HyperParams = HyperParams(),
    paths: Paths = Paths(),
    seed: int = 42,
    max_replays_per_source: int = 0,
):
    """Full MSC ingestion: load sparse matrices, extract features, label, save."""
    rng = np.random.default_rng(seed)
    msc_base = paths.data / "msc" / "parsed_replays" / "GlobalFeatureVector"
    output = paths.data / "msc"

    if not msc_base.exists():
        print(f"MSC data not found at {msc_base}")
        return

    stats_cache = {}
    minutes = [2, 3, 4, 5]

    all_labelled: Dict[str, list] = {m: [] for m in MATCHUPS}
    stats = {"total": 0, "labelled": 0, "excluded": 0, "parse_error": 0}

    for matchup_dir, subdir, stat_race, player_race, our_matchup in MSC_SOURCES:
        source_dir = msc_base / matchup_dir / subdir
        if not source_dir.exists():
            print(f"  {matchup_dir}/{subdir}: not found, skipping")
            continue

        if stat_race not in stats_cache:
            stat_path = paths.data / "msc" / f"{stat_race}.json"
            if not stat_path.exists():
                print(f"  Stat file {stat_path} not found, skipping {matchup_dir}/{subdir}")
                continue
            stats_cache[stat_race] = _load_stat(stat_path)

        stat = stats_cache[stat_race]
        units_offset, n_unit_slots, max_unit_num = _compute_offsets(stat)

        npz_files = sorted([f for f in source_dir.iterdir() if f.suffix == ".npz"])
        if max_replays_per_source > 0:
            npz_files = npz_files[:max_replays_per_source]

        print(f"  {matchup_dir}/{subdir} ({player_race} → {our_matchup}): "
              f"{len(npz_files)} files...")

        source_labelled = 0
        for npz_path in npz_files:
            try:
                f = np.load(str(npz_path))
                mat = csc_matrix(
                    (f["data"], f["indices"], f["indptr"]),
                    shape=tuple(f["shape"]),
                )
                dense = mat.toarray()
            except Exception:
                stats["parse_error"] += 1
                continue

            stats["total"] += 1

            build_order = _extract_build_order(
                dense, stat, units_offset, n_unit_slots, max_unit_num,
            )
            if not build_order:
                stats["excluded"] += 1
                continue

            label, source = label_replay(build_order, player_race)
            if label is None:
                stats["excluded"] += 1
                continue

            player_features = _extract_per_second_features(
                dense, stat, units_offset, n_unit_slots, max_unit_num,
            )
            all_labelled[our_matchup].append((player_features, label))
            source_labelled += 1

        stats["labelled"] += source_labelled
        print(f"    → {source_labelled} labelled")

    print(f"\nLabelling stats: {stats['total']} total, {stats['labelled']} labelled, "
          f"{stats['excluded']} excluded, {stats['parse_error']} parse errors")

    print("\nStep 2: Building features and saving...")
    for matchup in MATCHUPS:
        archetypes = archetypes_for_matchup(matchup)
        arch_to_idx = {a: i for i, a in enumerate(archetypes)}
        entries = all_labelled.get(matchup, [])

        if not entries:
            print(f"  {matchup}: no labelled replays, skipping")
            continue

        samples = []
        replay_ids = []
        replay_labels = []

        for replay_id, (player_features, label) in enumerate(entries):
            if label not in arch_to_idx:
                continue
            label_idx = arch_to_idx[label]

            duration = player_features.shape[0]
            opponent_features = np.zeros_like(player_features)
            mask = generate_scouting_mask(duration, rng)

            has_any = False
            for minute in minutes:
                if minute * 60 > duration:
                    continue
                temporal = build_temporal_features(
                    player_features, opponent_features, mask, minute, hp,
                )
                map_tensor = build_map_tensor("Unknown")
                samples.append((temporal, map_tensor, label_idx))
                has_any = True

            if has_any:
                replay_ids.append(replay_id)
                replay_labels.append(label_idx)

        if not samples:
            print(f"  {matchup}: no valid samples")
            continue

        train_ids, val_ids, test_ids = per_replay_split(
            replay_ids, replay_labels, seed=seed,
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

        from collections import Counter
        label_counts = Counter(s[2] for s in train_samples)
        per_class = {archetypes[k]: v for k, v in sorted(label_counts.items())}
        print(f"  {matchup}: {len(train_samples)} train, {len(val_samples)} val, "
              f"{len(test_samples)} test ({len(archetypes)} classes)")
        print(f"    per-class (train): {per_class}")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--max-per-source", type=int, default=0,
                        help="Max replays per MSC source (0 = all)")
    args = parser.parse_args()
    ingest_msc(max_replays_per_source=args.max_per_source)
