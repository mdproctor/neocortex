"""Generate synthetic training data to validate the pipeline end-to-end.

Creates fake but structurally valid replays with known archetype labels,
enough to verify: feature engineering → dataset → model → training → export → evaluation.

Usage: python3 -m evaluation.strategy_classifier.generate_synthetic
"""
import numpy as np
from pathlib import Path
from typing import Dict, List, Tuple
from evaluation.strategy_classifier.config import (
    ARCHETYPES, MATCHUPS, HyperParams, Paths, archetypes_for_matchup,
)
from evaluation.strategy_classifier.feature_engineering import (
    build_temporal_features, build_map_tensor, F_MAP,
)
from evaluation.strategy_classifier.fog_of_war import generate_scouting_mask
from evaluation.strategy_classifier.dataset import per_replay_split

N_FEATURES_PER_PLAYER = 50
REPLAYS_PER_ARCHETYPE = 40
GAME_DURATION_SECONDS = 300
MINUTES = [2, 3, 4, 5]


def _archetype_signal(archetype: str, n_features: int, rng: np.random.Generator) -> np.ndarray:
    """Generate a feature bias vector that makes this archetype distinguishable."""
    seed_val = hash(archetype) % 10000
    arch_rng = np.random.default_rng(seed_val)
    bias = arch_rng.standard_normal(n_features).astype(np.float32) * 0.5
    noise = rng.standard_normal(n_features).astype(np.float32) * 0.2
    return bias + noise


def generate_replay(
    archetype: str,
    n_features: int,
    duration_seconds: int,
    rng: np.random.Generator,
) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Generate one synthetic replay (player features, opponent features, scouting mask)."""
    signal = _archetype_signal(archetype, n_features, rng)

    player = rng.standard_normal((duration_seconds, n_features)).astype(np.float32) * 0.3
    opponent = np.zeros((duration_seconds, n_features), dtype=np.float32)
    for t in range(duration_seconds):
        progress = t / duration_seconds
        opponent[t] = signal * progress + rng.standard_normal(n_features).astype(np.float32) * 0.1

    mask = generate_scouting_mask(duration_seconds, rng)
    return player, opponent, mask


def generate_matchup_data(
    matchup: str, hp: HyperParams, rng: np.random.Generator,
) -> Tuple[List[Tuple[np.ndarray, np.ndarray, int]], List[int]]:
    """Generate all samples for one matchup. Returns (samples, replay_ids)."""
    archetypes = archetypes_for_matchup(matchup)
    all_samples = []
    replay_ids = []
    replay_labels = []
    map_names = ["Abyssal Reef LE", "Frost LE", "Odyssey LE", "Mech Depot LE", "Catallena LE"]

    replay_id = 0
    for arch_idx, archetype in enumerate(archetypes):
        for _ in range(REPLAYS_PER_ARCHETYPE):
            player, opponent, mask = generate_replay(
                archetype, N_FEATURES_PER_PLAYER, GAME_DURATION_SECONDS, rng
            )
            map_name = map_names[replay_id % len(map_names)]
            map_tensor = build_map_tensor(map_name)

            for minute in MINUTES:
                temporal = build_temporal_features(player, opponent, mask, minute, hp)
                all_samples.append((temporal, map_tensor, arch_idx))

            replay_ids.append(replay_id)
            replay_labels.append(arch_idx)
            replay_id += 1

    return all_samples, replay_ids, replay_labels


def generate_all(hp: HyperParams = HyperParams(), paths: Paths = Paths(), seed: int = 42):
    """Generate synthetic data for all matchups and save splits."""
    rng = np.random.default_rng(seed)
    output = paths.data / "synthetic"
    output.mkdir(parents=True, exist_ok=True)

    for matchup in MATCHUPS:
        print(f"Generating {matchup}...")
        samples, replay_ids, replay_labels = generate_matchup_data(matchup, hp, rng)

        train_ids, val_ids, test_ids = per_replay_split(replay_ids, replay_labels, seed=seed)
        train_id_set = set(train_ids)
        val_id_set = set(val_ids)
        test_id_set = set(test_ids)

        train_samples, val_samples, test_samples = [], [], []
        samples_per_replay = len(MINUTES)
        for rid in replay_ids:
            start = rid * samples_per_replay
            end = start + samples_per_replay
            replay_samples = samples[start:end]
            if rid in train_id_set:
                train_samples.extend(replay_samples)
            elif rid in val_id_set:
                val_samples.extend(replay_samples)
            else:
                test_samples.extend(replay_samples)

        matchup_dir = output / matchup
        matchup_dir.mkdir(parents=True, exist_ok=True)

        _save_split(train_samples, matchup_dir / "train.npz")
        _save_split(val_samples, matchup_dir / "val.npz")
        _save_split(test_samples, matchup_dir / "test.npz")

        archetypes = archetypes_for_matchup(matchup)
        print(f"  {matchup}: {len(train_samples)} train, {len(val_samples)} val, "
              f"{len(test_samples)} test ({len(archetypes)} classes)")

    print(f"\nSynthetic data saved to {output}")


def _save_split(samples, path: Path):
    if not samples:
        return
    temporal = np.array([s[0] for s in samples])
    map_feat = np.array([s[1] for s in samples])
    labels = np.array([s[2] for s in samples])
    np.savez_compressed(path, temporal=temporal, map_features=map_feat, labels=labels)


def load_split(path: Path) -> List[Tuple[np.ndarray, np.ndarray, int]]:
    """Load a saved split back into sample tuples."""
    data = np.load(path)
    temporal = data["temporal"]
    map_features = data["map_features"]
    labels = data["labels"]
    samples = [
        (temporal[i], map_features[i], int(labels[i]))
        for i in range(len(labels))
    ]
    return samples


if __name__ == "__main__":
    generate_all()
