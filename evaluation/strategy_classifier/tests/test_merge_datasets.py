import numpy as np
import pytest
from evaluation.strategy_classifier.merge_datasets import (
    consolidate_labels, CONSOLIDATION_MAP,
)


def test_consolidate_labels_below_threshold():
    archetypes = ["RUSH", "ROACH_RUSH", "LING_BANE", "MUTA_HARASS",
                  "HYDRA_PUSH", "MACRO_ECONOMY", "TECH_RUSH"]
    labels = np.array(
        [0]*60 + [1]*200 + [2]*100 + [3]*30 + [4]*20 + [5]*10 + [6]*3
    )
    new_labels, new_archetypes, consolidations = consolidate_labels(
        labels, archetypes, min_samples=50,
    )
    assert "TECH_RUSH" not in new_archetypes
    assert "HYDRA_PUSH" not in new_archetypes
    assert "MUTA_HARASS" not in new_archetypes
    assert len(consolidations) == 3
    assert len(new_labels) == len(labels)
    assert max(new_labels) < len(new_archetypes)


def test_consolidate_labels_above_threshold():
    archetypes = ["RUSH", "ROACH_RUSH", "LING_BANE"]
    labels = np.array([0]*100 + [1]*200 + [2]*100)
    new_labels, new_archetypes, consolidations = consolidate_labels(
        labels, archetypes, min_samples=50,
    )
    assert new_archetypes == archetypes
    assert consolidations == []
    np.testing.assert_array_equal(new_labels, labels)


def test_consolidate_preserves_total_count():
    archetypes = ["RUSH", "ROACH_RUSH", "LING_BANE", "MUTA_HARASS",
                  "HYDRA_PUSH", "MACRO_ECONOMY", "TECH_RUSH"]
    labels = np.array(
        [0]*60 + [1]*200 + [2]*100 + [3]*30 + [4]*20 + [5]*10 + [6]*3
    )
    new_labels, _, _ = consolidate_labels(labels, archetypes, min_samples=50)
    assert len(new_labels) == len(labels)


def test_consolidate_maps_tech_rush_to_rush():
    archetypes = ["RUSH", "TECH_RUSH"]
    labels = np.array([0]*100 + [1]*3)
    new_labels, new_archetypes, consolidations = consolidate_labels(
        labels, archetypes, min_samples=50,
    )
    assert new_archetypes == ["RUSH"]
    assert ("TECH_RUSH", "RUSH") in consolidations
    assert all(l == 0 for l in new_labels)


def test_consolidate_keeps_class_when_at_threshold():
    archetypes = ["RUSH", "TECH_RUSH"]
    labels = np.array([0]*100 + [1]*50)
    new_labels, new_archetypes, consolidations = consolidate_labels(
        labels, archetypes, min_samples=50,
    )
    assert "TECH_RUSH" in new_archetypes
    assert consolidations == []


def test_consolidation_map_targets_exist():
    all_archetypes = set()
    from evaluation.strategy_classifier.config import ARCHETYPES
    for matchup_archs in ARCHETYPES.values():
        all_archetypes.update(matchup_archs)
    for source, target in CONSOLIDATION_MAP.items():
        assert source in all_archetypes, f"{source} not in any matchup"
        assert target in all_archetypes, f"{target} not in any matchup"
