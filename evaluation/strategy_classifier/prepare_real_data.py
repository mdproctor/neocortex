"""Prepare real SC2EGSet data for training: extract → label → save.

Processes each SC2EGSet ZIP independently into a per-tournament subdirectory
under data/sc2egset/<tournament_name>/. Provides resume support — ZIPs whose
output directory already exists are skipped. Never holds more than one
tournament in memory (OOM safety for 70+ ZIPs).

Usage: python3 -m evaluation.strategy_classifier.prepare_real_data \
         --zips <path1.zip> [<path2.zip> ...] \
         [--llm]      # enable LLM labelling for ambiguous replays
         [--force]    # reprocess even if output dir exists
"""
import gc
import json
import sys
import numpy as np
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from evaluation.strategy_classifier.config import (
    MATCHUPS, HyperParams, Paths, archetypes_for_matchup,
)
from evaluation.strategy_classifier.sc2egset_extractor import (
    extract_from_zip, ReplayData, LOOPS_PER_SECOND, BUILDING_IDX, BUILDINGS,
    build_samples_from_replays, extract_replay,
)
from evaluation.strategy_classifier.feature_engineering import (
    build_temporal_features, build_map_tensor, F_MAP,
)
from evaluation.strategy_classifier.fog_of_war import generate_scouting_mask
from evaluation.strategy_classifier.dataset import per_replay_split
from evaluation.strategy_classifier.labelling.rules import rule_based_label
from evaluation.strategy_classifier.labelling.label_pipeline import label_replay

MINUTES = [2, 3, 4, 5]


def tournament_name(zip_path: Path) -> str:
    """Derive tournament name from ZIP filename (strip .zip extension)."""
    return zip_path.stem


def extract_build_order(game_json: dict, player_id: int) -> List[Dict]:
    """Extract a player's build order from tracker events."""
    build_order = []
    for e in game_json.get("trackerEvents", []):
        etype = e.get("evtTypeName", "")
        pid = e.get("controlPlayerId", 0)
        if pid != player_id:
            continue

        loop = e.get("loop", 0)
        minute = loop / LOOPS_PER_SECOND / 60.0

        if etype == "UnitInit":
            unit_name = e.get("unitTypeName", "")
            if unit_name in BUILDING_IDX:
                build_order.append({
                    "type": "building", "name": unit_name, "minute": minute
                })

        elif etype == "UnitBorn":
            unit_name = e.get("unitTypeName", "")
            if unit_name not in BUILDING_IDX:
                build_order.append({
                    "type": "unit", "name": unit_name, "minute": minute
                })

    return sorted(build_order, key=lambda x: x["minute"])


def extract_build_orders_from_zip(zip_path: Path) -> List[Tuple[dict, Dict]]:
    """Extract raw game JSONs + metadata from a ZIP for labelling."""
    import zipfile, io
    outer = zipfile.ZipFile(zip_path)

    data_zip_name = None
    for name in outer.namelist():
        if name.endswith("_data.zip"):
            data_zip_name = name
            break

    if data_zip_name:
        inner_bytes = outer.read(data_zip_name)
        inner = zipfile.ZipFile(io.BytesIO(inner_bytes))
    else:
        inner = outer

    games = []
    for name in inner.namelist():
        if not name.endswith(".SC2Replay.json"):
            continue
        try:
            game = json.loads(inner.read(name))
            toon_map = game.get("ToonPlayerDescMap", {})
            if len(toon_map) != 2:
                continue

            players = {}
            for toon, desc in toon_map.items():
                pid = desc["playerID"]
                race_raw = desc.get("race", "")
                race = {"Terr": "Terran", "Prot": "Protoss", "Zerg": "Zerg"}.get(race_raw, race_raw)
                players[pid] = {"race": race, "result": desc.get("result", "")}

            if 1 in players and 2 in players:
                games.append((game, players))
        except Exception:
            continue

    return games


def label_replays_from_zip(
    zip_path: Path, use_llm: bool = False, llm_client=None,
) -> Dict[str, list]:
    """Label all replays from a single ZIP.

    Returns dict[matchup] -> list of (game_json, label, observer_id, opponent_id).
    """
    matchup_map = {
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

    labelled: Dict[str, list] = {m: [] for m in MATCHUPS}
    stats = {"rule": 0, "llm": 0, "excluded": 0, "total": 0}

    games = extract_build_orders_from_zip(zip_path)

    for game_json, players in games:
        for observer_id, opponent_id in [(1, 2), (2, 1)]:
            observer_race = players[observer_id]["race"]
            opponent_race = players[opponent_id]["race"]
            matchup_key = (observer_race, opponent_race)
            if matchup_key not in matchup_map:
                continue
            matchup = matchup_map[matchup_key]

            opponent_build = extract_build_order(game_json, opponent_id)
            label, source = label_replay(opponent_build, opponent_race, llm_client)

            stats["total"] += 1
            stats[source] += 1

            if label is not None:
                labelled[matchup].append((game_json, label, observer_id, opponent_id))

    return labelled, stats


def process_single_zip(
    zip_path: Path,
    output_base: Path,
    use_llm: bool = False,
    llm_client=None,
    hp: HyperParams = HyperParams(),
    seed: int = 42,
) -> Dict[str, int]:
    """Process a single SC2EGSet ZIP into per-tournament output.

    Writes to output_base/<tournament_name>/vs_terran/*.npz etc.
    Returns dict of matchup -> sample count for reporting.
    """
    name = tournament_name(zip_path)
    tournament_dir = output_base / name
    rng = np.random.default_rng(seed)

    print(f"\n{'='*60}")
    print(f"Processing {zip_path.name}")
    print(f"{'='*60}")

    print("  Labelling replays...")
    labelled, stats = label_replays_from_zip(zip_path, use_llm, llm_client)
    print(f"  Labelling: {stats['total']} total, "
          f"{stats['rule']} rule-based, {stats['llm']} LLM, "
          f"{stats['excluded']} excluded")

    sample_counts = {}

    for matchup in MATCHUPS:
        archetypes = archetypes_for_matchup(matchup)
        arch_to_idx = {a: i for i, a in enumerate(archetypes)}

        labelled_games = labelled.get(matchup, [])
        if not labelled_games:
            continue

        samples = []
        replay_ids = []
        replay_labels = []
        replay_id = 0

        for game_json, label, observer_id, opponent_id in labelled_games:
            if label not in arch_to_idx:
                continue
            label_idx = arch_to_idx[label]

            header = game_json.get("header", {})
            total_loops = header.get("elapsedGameLoops", 0)
            duration = min(int(total_loops / LOOPS_PER_SECOND), 600)

            replay = extract_replay(game_json)
            if replay is None:
                continue

            if observer_id == 1:
                own_feat = replay.player1_features
                opp_feat = replay.player2_features
            else:
                own_feat = replay.player2_features
                opp_feat = replay.player1_features

            map_tensor = build_map_tensor(replay.map_name)
            mask = generate_scouting_mask(duration, rng)

            has_any = False
            for minute in MINUTES:
                if minute * 60 > duration:
                    continue
                temporal = build_temporal_features(own_feat, opp_feat, mask, minute, hp)
                samples.append((temporal, map_tensor, label_idx))
                has_any = True

            if has_any:
                replay_ids.append(replay_id)
                replay_labels.append(label_idx)
                replay_id += 1

        if not samples:
            continue

        train_ids, val_ids, test_ids = per_replay_split(replay_ids, replay_labels, seed=seed)
        train_set = set(train_ids)
        val_set = set(val_ids)

        train_samples, val_samples, test_samples = [], [], []
        idx = 0
        for rid in replay_ids:
            count = 0
            while idx < len(samples) and count < len(MINUTES):
                if rid in train_set:
                    train_samples.append(samples[idx])
                elif rid in val_set:
                    val_samples.append(samples[idx])
                else:
                    test_samples.append(samples[idx])
                idx += 1
                count += 1

        matchup_dir = tournament_dir / matchup
        matchup_dir.mkdir(parents=True, exist_ok=True)
        _save_split(train_samples, matchup_dir / "train.npz")
        _save_split(val_samples, matchup_dir / "val.npz")
        _save_split(test_samples, matchup_dir / "test.npz")

        total = len(train_samples) + len(val_samples) + len(test_samples)
        sample_counts[matchup] = total
        print(f"  {matchup}: {len(train_samples)} train, {len(val_samples)} val, "
              f"{len(test_samples)} test ({len(replay_ids)} replays)")

    del labelled
    gc.collect()

    return sample_counts


def _save_split(samples, path: Path):
    if not samples:
        return
    temporal = np.array([s[0] for s in samples])
    map_feat = np.array([s[1] for s in samples])
    labels = np.array([s[2] for s in samples])
    np.savez_compressed(path, temporal=temporal, map_features=map_feat, labels=labels)


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--zips", nargs="+", required=True, type=Path)
    parser.add_argument("--llm", action="store_true", help="Enable LLM labelling for ambiguous replays")
    parser.add_argument("--force", action="store_true", help="Reprocess even if output dir exists")
    args = parser.parse_args()

    paths = Paths()
    output_base = paths.data / "sc2egset"

    llm_client = None
    if args.llm:
        try:
            from evaluation.strategy_classifier.labelling.llm_labeller import create_client
            llm_client = create_client()
            print("LLM labelling enabled")
        except Exception as e:
            print(f"Warning: LLM labelling requested but client failed: {e}")
            print("Falling back to rule-based only")

    total_zips = len(args.zips)
    skipped = 0
    processed = 0
    grand_totals = {m: 0 for m in MATCHUPS}

    for i, zip_path in enumerate(args.zips, 1):
        name = tournament_name(zip_path)
        tournament_dir = output_base / name

        if tournament_dir.exists() and not args.force:
            print(f"[{i}/{total_zips}] Skipping {name} (output exists)")
            skipped += 1
            continue

        print(f"[{i}/{total_zips}]", end="")
        counts = process_single_zip(
            zip_path, output_base, args.llm, llm_client,
        )
        for m, c in counts.items():
            grand_totals[m] += c
        processed += 1
        gc.collect()

    print(f"\n{'='*60}")
    print(f"Summary: {processed} processed, {skipped} skipped (of {total_zips} ZIPs)")
    if processed > 0:
        for m in MATCHUPS:
            if grand_totals[m] > 0:
                print(f"  {m}: {grand_totals[m]} new samples")
    print(f"\nNext: python3 -m evaluation.strategy_classifier.normalize "
          f"--sources sc2egset spawningtool msc")
