import pytest
from evaluation.strategy_classifier.config import (
    ARCHETYPES, MATCHUPS, HyperParams, Paths,
    archetypes_for_matchup, all_archetype_names,
    COARSE_HIERARCHY, coarse_label_map,
)


class TestArchetypeTaxonomy:
    def test_three_matchups(self):
        assert set(MATCHUPS) == {"vs_terran", "vs_zerg", "vs_protoss"}

    def test_vs_terran_has_expected_archetypes(self):
        archetypes = archetypes_for_matchup("vs_terran")
        assert "RUSH" in archetypes
        assert "BANSHEE_HARASS" in archetypes
        assert "MECH_PUSH" in archetypes
        assert "MACRO_ECONOMY" in archetypes

    def test_vs_zerg_has_expected_archetypes(self):
        archetypes = archetypes_for_matchup("vs_zerg")
        assert "LING_BANE" in archetypes
        assert "MUTA_HARASS" in archetypes
        assert "ROACH_RUSH" in archetypes

    def test_vs_protoss_has_expected_archetypes(self):
        archetypes = archetypes_for_matchup("vs_protoss")
        assert "CANNON_RUSH" in archetypes
        assert "DT_RUSH" in archetypes
        assert "BLINK_STALKER" in archetypes

    def test_per_matchup_counts(self):
        assert len(archetypes_for_matchup("vs_terran")) == 8
        assert len(archetypes_for_matchup("vs_zerg")) == 7
        assert len(archetypes_for_matchup("vs_protoss")) == 9

    def test_all_archetypes_unique(self):
        names = all_archetype_names()
        assert len(names) == len(set(names))


class TestCoarseHierarchy:
    def test_vs_terran_hierarchy_covers_all_archetypes(self):
        hierarchy = COARSE_HIERARCHY["vs_terran"]
        all_fine = []
        for fine_list in hierarchy.values():
            all_fine.extend(fine_list)
        terran_archetypes = archetypes_for_matchup("vs_terran")
        for arch in all_fine:
            assert arch in terran_archetypes, f"{arch} not in vs_terran archetypes"

    def test_coarse_label_map_vs_terran(self):
        fine_classes = ["RUSH", "BANSHEE_HARASS", "AIR_SUPERIORITY", "MECH_PUSH", "BIO_TIMING"]
        mapping = coarse_label_map("vs_terran", fine_classes)
        assert mapping == [0, 1, 1, 2, 2]

    def test_coarse_label_map_no_hierarchy(self):
        mapping = coarse_label_map("vs_zerg", ["RUSH", "ROACH_RUSH"])
        assert mapping == []


class TestHyperParams:
    def test_defaults(self):
        hp = HyperParams()
        assert hp.lr == 1e-3
        assert hp.batch_size == 128
        assert hp.max_epochs == 50
        assert hp.patience == 10
        assert hp.focal_gamma == 2.0
        assert hp.dropout == 0.3
        assert hp.window_seconds == 30
        assert hp.max_windows == 10
        assert hp.conv_channels == [64, 128]
        assert hp.dense_hidden == 64
