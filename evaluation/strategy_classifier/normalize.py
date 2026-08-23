"""Compute normalization stats from SC2EGSet and merge multi-source datasets.

Standardizes all temporal features using mean/std from the clean SC2EGSet
source. Adds availability flags (has_player, has_opponent) to map_feat
based on zero-block detection. Consolidates rare archetypes below threshold.

Usage: python3 -m evaluation.strategy_classifier.normalize \
         --sources sc2egset spawningtool msc \
         [--min-samples 50]
"""
import gc
import json
import numpy as np
from collections import Counter
from pathlib import Path
from typing import Dict, List, Tuple
from evaluation.strategy_classifier.config import (
    MATCHUPS, Paths, archetypes_for_matchup,
)
from evaluation.strategy_classifier.merge_datasets import (
    consolidate_labels, _apply_same_remap,
)

from evaluation.strategy_classifier.sc2egset_extractor import N_FEATURES_PER_PLAYER
N_PLAYER_FEATURES = N_FEATURES_PER_PLAYER
N_OPPONENT_FEATURES = N_FEATURES_PER_PLAYER


def _find_split_files(source_dir: Path, matchup: str, split_name: str) -> List[Path]:
    """Find split NPZ files across per-tournament subdirectories or flat layout.

    Supports both layouts:
      - Per-tournament: sc2egset/<tournament>/vs_terran/train.npz
      - Flat (legacy):  sc2egset/vs_terran/train.npz
    """
    files = []
    flat = source_dir / matchup / f"{split_name}.npz"
    if flat.exists():
        files.append(flat)
    for subdir in sorted(source_dir.iterdir()):
        if not subdir.is_dir() or subdir.name.startswith("raw") or subdir.name in MATCHUPS:
            continue
        p = subdir / matchup / f"{split_name}.npz"
        if p.exists():
            files.append(p)
    return files


def compute_stats(sc2egset_dir: Path) -> Dict[str, np.ndarray]:
    """Compute per-feature mean and std from SC2EGSet training data.

    Reads across per-tournament subdirectories for streaming stat computation.
    Uses Welford's online algorithm to avoid loading all data into memory.
    """
    count = 0
    mean_acc = None
    m2_acc = None

    for matchup in MATCHUPS:
        for train_path in _find_split_files(sc2egset_dir, matchup, "train"):
            d = np.load(str(train_path))
            t = d["temporal"].reshape(-1, d["temporal"].shape[-1])
            del d

            if mean_acc is None:
                mean_acc = np.zeros(t.shape[1], dtype=np.float64)
                m2_acc = np.zeros(t.shape[1], dtype=np.float64)

            for row in t:
                count += 1
                delta = row - mean_acc
                mean_acc += delta / count
                delta2 = row - mean_acc
                m2_acc += delta * delta2

            del t
            gc.collect()

    if count == 0:
        raise ValueError(f"No SC2EGSet training data found in {sc2egset_dir}")

    mean = mean_acc.astype(np.float32)
    std = np.sqrt(m2_acc / count).astype(np.float32)
    std = np.maximum(std, 1e-6)

    vis_idx = 2 * N_PLAYER_FEATURES
    mean[vis_idx] = 0.0
    std[vis_idx] = 1.0

    return {"mean": mean, "std": std}


def detect_availability(temporal: np.ndarray) -> Tuple[float, float]:
    """Detect whether player and opponent feature blocks are populated."""
    player_block = temporal[:, :N_PLAYER_FEATURES]
    opponent_block = temporal[:, N_PLAYER_FEATURES:N_PLAYER_FEATURES + N_OPPONENT_FEATURES]

    has_player = 1.0 if np.abs(player_block).sum() > 0 else 0.0
    has_opponent = 1.0 if np.abs(opponent_block).sum() > 0 else 0.0
    return has_player, has_opponent


def normalize_and_merge(
    sources: List[str],
    stats: Dict[str, np.ndarray],
    paths: Paths = Paths(),
    min_samples: int = 50,
):
    """Merge all sources with normalization and availability flags."""
    data_base = paths.data
    output = data_base / "combined"
    output.mkdir(parents=True, exist_ok=True)

    mean = stats["mean"]
    std = stats["std"]

    for matchup in MATCHUPS:
        archetypes = archetypes_for_matchup(matchup)
        consolidations = []
        compact_map = None
        final_archetypes = archetypes

        for split_name in ["train", "val", "test"]:
            all_temporal, all_map, all_labels = [], [], []

            for source in sources:
                source_dir = data_base / source
                split_files = _find_split_files(source_dir, matchup, split_name)

                for p in split_files:
                    d = np.load(str(p))
                    t = d["temporal"]
                    m = d["map_features"]
                    l = d["labels"]

                    n_samples, n_windows, n_features = t.shape
                    t_flat = t.reshape(-1, n_features)
                    t_norm = ((t_flat - mean) / std).reshape(n_samples, n_windows, n_features)

                    has_player_arr = np.zeros(n_samples, dtype=np.float32)
                    has_opponent_arr = np.zeros(n_samples, dtype=np.float32)
                    for i in range(n_samples):
                        hp, ho = detect_availability(t[i])
                        has_player_arr[i] = hp
                        has_opponent_arr[i] = ho

                    m_ext = np.column_stack([m, has_player_arr, has_opponent_arr])

                    all_temporal.append(t_norm)
                    all_map.append(m_ext)
                    all_labels.append(l)

                    rel = p.relative_to(data_base)
                    n_hp = int(has_player_arr.sum())
                    n_ho = int(has_opponent_arr.sum())
                    print(f"  {rel}: {n_samples} samples "
                          f"(player:{n_hp}/{n_samples}, opponent:{n_ho}/{n_samples})")
                    del d, t, m, l, t_flat, t_norm
                    gc.collect()

            if not all_temporal:
                continue

            temporal = np.concatenate(all_temporal)
            map_feat = np.concatenate(all_map)
            labels = np.concatenate(all_labels)
            del all_temporal, all_map, all_labels
            gc.collect()

            if split_name == "train":
                new_labels, new_archetypes, consolidations = consolidate_labels(
                    labels, archetypes, min_samples=min_samples,
                )
                if consolidations:
                    print(f"  {matchup}: consolidated {consolidations}")
                    labels = new_labels
                counts = Counter(labels.tolist())
                present = sorted(counts.keys())
                if present != list(range(len(new_archetypes))):
                    compact_map = {old: new for new, old in enumerate(present)}
                    labels = np.array([compact_map[l] for l in labels])
                    new_archetypes = [new_archetypes[i] for i in present]
                    counts = Counter(labels.tolist())
                final_archetypes = new_archetypes
                per_class = {
                    (final_archetypes[k] if k < len(final_archetypes) else f"?{k}"): v
                    for k, v in sorted(counts.items())
                }
                print(f"  {matchup}/{split_name}: {len(labels)} total, {per_class}")
            else:
                labels = _apply_same_remap(labels, archetypes, consolidations)
                if compact_map:
                    mask = np.array([l in compact_map for l in labels])
                    if not mask.all():
                        n_dropped = int((~mask).sum())
                        print(f"  {matchup}/{split_name}: dropped {n_dropped} samples "
                              f"with classes removed during compaction")
                        temporal = temporal[mask]
                        map_feat = map_feat[mask]
                        labels = labels[mask]
                    labels = np.array([compact_map[l] for l in labels])

            matchup_dir = output / matchup
            matchup_dir.mkdir(parents=True, exist_ok=True)
            np.savez_compressed(
                str(matchup_dir / f"{split_name}.npz"),
                temporal=temporal, map_features=map_feat, labels=labels,
            )
            del temporal, map_feat, labels
            gc.collect()

        matchup_dir = output / matchup
        matchup_dir.mkdir(parents=True, exist_ok=True)
        with open(matchup_dir / "classes.json", "w") as f:
            json.dump(final_archetypes, f)

    stats_path = output / "norm_stats.npz"
    np.savez(str(stats_path), mean=mean, std=std)
    print(f"\nNormalization stats saved to {stats_path}")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--sources", nargs="+",
                        default=["sc2egset", "spawningtool", "msc"])
    parser.add_argument("--min-samples", type=int, default=50)
    args = parser.parse_args()

    paths = Paths()
    print("Computing normalization stats from SC2EGSet...")
    stats = compute_stats(paths.data / "sc2egset")
    print(f"  Mean range: [{stats['mean'].min():.4f}, {stats['mean'].max():.4f}]")
    print(f"  Std range: [{stats['std'].min():.4f}, {stats['std'].max():.4f}]")

    print(f"\nMerging sources: {args.sources}")
    normalize_and_merge(args.sources, stats, paths, min_samples=args.min_samples)
    print("\nDone.")
