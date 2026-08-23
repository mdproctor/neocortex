"""Merge datasets from multiple sources and consolidate rare archetypes.

Usage: python3 -m evaluation.strategy_classifier.merge_datasets \
         --sources sc2egset msc spawningtool \
         [--min-samples 50]
"""
import numpy as np
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Tuple
from evaluation.strategy_classifier.config import (
    MATCHUPS, Paths, archetypes_for_matchup,
)
from evaluation.strategy_classifier.generate_synthetic import load_split

CONSOLIDATION_MAP = {
    "TECH_RUSH": "RUSH",
    "HYDRA_PUSH": "MACRO_ECONOMY",
    "MUTA_HARASS": "LING_BANE",
    "AIR_SUPERIORITY": "BANSHEE_HARASS",
    "MACRO_ECONOMY": "BIO_TIMING",
}


@dataclass
class MergeReport:
    matchup: str
    per_source_counts: Dict[str, int] = field(default_factory=dict)
    per_class_counts: Dict[str, int] = field(default_factory=dict)
    consolidations: List[Tuple[str, str]] = field(default_factory=list)
    final_archetypes: List[str] = field(default_factory=list)
    train_count: int = 0
    val_count: int = 0
    test_count: int = 0


def consolidate_labels(
    labels: np.ndarray,
    archetypes: List[str],
    min_samples: int = 50,
) -> Tuple[np.ndarray, List[str], List[Tuple[str, str]]]:
    counts = Counter(labels.tolist())
    consolidations = []
    remap = {}

    for idx, name in enumerate(archetypes):
        if counts.get(idx, 0) < min_samples and name in CONSOLIDATION_MAP:
            parent = CONSOLIDATION_MAP[name]
            if parent in archetypes:
                parent_idx = archetypes.index(parent)
                remap[idx] = parent_idx
                consolidations.append((name, parent))

    if not remap:
        return labels, list(archetypes), []

    new_labels = np.array([remap.get(l, l) for l in labels])

    surviving = sorted(set(range(len(archetypes))) - set(remap.keys()))
    new_archetypes = [archetypes[i] for i in surviving]

    idx_remap = {}
    for new_idx, old_idx in enumerate(surviving):
        idx_remap[old_idx] = new_idx
    for old_idx, parent_idx in remap.items():
        idx_remap[old_idx] = idx_remap[parent_idx]

    final_labels = np.array([idx_remap[l] for l in new_labels])
    return final_labels, new_archetypes, consolidations


def _save_split(samples, path: Path):
    if not samples:
        return
    temporal = np.array([s[0] for s in samples])
    map_feat = np.array([s[1] for s in samples])
    labels = np.array([s[2] for s in samples])
    np.savez_compressed(path, temporal=temporal, map_features=map_feat, labels=labels)


def merge_all(
    sources: List[str] = None,
    paths: Paths = Paths(),
    min_samples: int = 50,
    seed: int = 42,
) -> Dict[str, MergeReport]:
    if sources is None:
        sources = ["sc2egset", "msc", "spawningtool"]

    output = paths.data / "combined"
    output.mkdir(parents=True, exist_ok=True)
    reports = {}

    for matchup in MATCHUPS:
        archetypes = archetypes_for_matchup(matchup)
        report = MergeReport(matchup=matchup)

        all_train, all_val, all_test = [], [], []

        for source in sources:
            source_dir = paths.data / source / matchup
            if not source_dir.exists():
                print(f"  {matchup}/{source}: not found, skipping")
                continue

            train_path = source_dir / "train.npz"
            val_path = source_dir / "val.npz"
            test_path = source_dir / "test.npz"

            source_count = 0
            for split_path, target_list in [
                (train_path, all_train),
                (val_path, all_val),
                (test_path, all_test),
            ]:
                if split_path.exists():
                    samples = load_split(split_path)
                    target_list.extend(samples)
                    source_count += len(samples)

            report.per_source_counts[source] = source_count

        if not all_train:
            print(f"  {matchup}: no training data from any source")
            continue

        train_labels = np.array([s[2] for s in all_train])
        for idx, name in enumerate(archetypes):
            count = int(np.sum(train_labels == idx))
            report.per_class_counts[name] = count

        new_train_labels, new_archetypes, consolidations = consolidate_labels(
            train_labels, archetypes, min_samples=min_samples,
        )
        report.consolidations = consolidations
        report.final_archetypes = new_archetypes

        if consolidations:
            print(f"  {matchup}: consolidating {len(consolidations)} classes:")
            for src_name, tgt_name in consolidations:
                print(f"    {src_name} → {tgt_name}")

            all_train = [
                (s[0], s[1], int(new_train_labels[i]))
                for i, s in enumerate(all_train)
            ]

            val_labels = np.array([s[2] for s in all_val])
            new_val_labels, _, _ = consolidate_labels(
                val_labels, archetypes, min_samples=0,
            )
            new_val_labels = _apply_same_remap(val_labels, archetypes, consolidations)
            all_val = [
                (s[0], s[1], int(new_val_labels[i]))
                for i, s in enumerate(all_val)
            ]

            test_labels = np.array([s[2] for s in all_test])
            new_test_labels = _apply_same_remap(test_labels, archetypes, consolidations)
            all_test = [
                (s[0], s[1], int(new_test_labels[i]))
                for i, s in enumerate(all_test)
            ]

        report.train_count = len(all_train)
        report.val_count = len(all_val)
        report.test_count = len(all_test)

        matchup_dir = output / matchup
        matchup_dir.mkdir(parents=True, exist_ok=True)
        _save_split(all_train, matchup_dir / "train.npz")
        _save_split(all_val, matchup_dir / "val.npz")
        _save_split(all_test, matchup_dir / "test.npz")

        print(f"  {matchup}: {report.train_count} train, {report.val_count} val, "
              f"{report.test_count} test "
              f"({len(new_archetypes)} classes, "
              f"sources: {report.per_source_counts})")

        reports[matchup] = report

    return reports


def _apply_same_remap(
    labels: np.ndarray,
    archetypes: List[str],
    consolidations: List[Tuple[str, str]],
) -> np.ndarray:
    remap = {}
    for src_name, tgt_name in consolidations:
        if src_name in archetypes and tgt_name in archetypes:
            remap[archetypes.index(src_name)] = archetypes.index(tgt_name)

    remapped = np.array([remap.get(l, l) for l in labels])

    surviving = sorted(set(range(len(archetypes))) - set(remap.keys()))
    idx_remap = {}
    for new_idx, old_idx in enumerate(surviving):
        idx_remap[old_idx] = new_idx
    for old_idx, parent_idx in remap.items():
        idx_remap[old_idx] = idx_remap[parent_idx]

    return np.array([idx_remap[l] for l in remapped])


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--sources", nargs="+",
                        default=["sc2egset", "msc", "spawningtool"])
    parser.add_argument("--min-samples", type=int, default=50)
    args = parser.parse_args()
    reports = merge_all(sources=args.sources, min_samples=args.min_samples)

    print(f"\n{'='*60}")
    print("Merge complete.")
    for matchup, report in reports.items():
        print(f"\n  {matchup}:")
        print(f"    Classes: {report.final_archetypes}")
        print(f"    Per-class (train): {report.per_class_counts}")
        if report.consolidations:
            print(f"    Consolidated: {report.consolidations}")
    print(f"{'='*60}")
