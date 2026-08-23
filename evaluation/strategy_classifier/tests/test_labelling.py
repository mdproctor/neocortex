import pytest
from evaluation.strategy_classifier.labelling.rules import rule_based_label


class TestZergLabelling:
    def test_early_pool_is_rush(self):
        build = [
            {"type": "building", "name": "SpawningPool", "minute": 0.6},
            {"type": "unit", "name": "Zergling", "minute": 1.2},
        ]
        assert rule_based_label(build, "Zerg") == "RUSH"

    def test_standard_hatch_first_not_rush(self):
        build = [
            {"type": "building", "name": "Hatchery", "minute": 0.0},
            {"type": "building", "name": "Extractor", "minute": 0.9},
            {"type": "building", "name": "SpawningPool", "minute": 1.2},
            {"type": "building", "name": "Hatchery", "minute": 1.5},
            {"type": "building", "name": "BanelingNest", "minute": 5.0},
        ]
        label = rule_based_label(build, "Zerg")
        assert label != "RUSH"

    def test_early_roach_warren(self):
        build = [
            {"type": "building", "name": "Hatchery", "minute": 0.0},
            {"type": "building", "name": "SpawningPool", "minute": 1.2},
            {"type": "building", "name": "Hatchery", "minute": 1.5},
            {"type": "building", "name": "RoachWarren", "minute": 3.5},
        ]
        assert rule_based_label(build, "Zerg") == "ROACH_RUSH"

    def test_baneling_nest_is_ling_bane(self):
        build = [
            {"type": "building", "name": "Hatchery", "minute": 0.0},
            {"type": "building", "name": "SpawningPool", "minute": 1.2},
            {"type": "building", "name": "Hatchery", "minute": 1.5},
            {"type": "building", "name": "BanelingNest", "minute": 5.5},
        ]
        assert rule_based_label(build, "Zerg") == "LING_BANE"


class TestTerranLabelling:
    def test_factory_heavy_is_mech(self):
        build = [
            {"type": "building", "name": "Barracks", "minute": 0.8},
            {"type": "building", "name": "Factory", "minute": 2.0},
            {"type": "building", "name": "Factory", "minute": 4.0},
        ]
        assert rule_based_label(build, "Terran") == "MECH_PUSH"

    def test_cc_first_is_macro(self):
        build = [
            {"type": "building", "name": "CommandCenter", "minute": 0.0},
            {"type": "building", "name": "CommandCenter", "minute": 2.0},
            {"type": "building", "name": "Barracks", "minute": 1.0},
            {"type": "building", "name": "Factory", "minute": 3.5},
        ]
        assert rule_based_label(build, "Terran") == "MACRO_ECONOMY"

    def test_cc_first_into_mech_is_not_macro(self):
        build = [
            {"type": "building", "name": "CommandCenter", "minute": 0.0},
            {"type": "building", "name": "Barracks", "minute": 1.0},
            {"type": "building", "name": "CommandCenter", "minute": 2.0},
            {"type": "building", "name": "Factory", "minute": 2.5},
            {"type": "building", "name": "Factory", "minute": 4.0},
            {"type": "unit", "name": "SiegeTank", "minute": 4.5},
        ]
        assert rule_based_label(build, "Terran") == "MECH_PUSH"

    def test_cc_first_into_bio_is_not_macro(self):
        build = [
            {"type": "building", "name": "CommandCenter", "minute": 0.0},
            {"type": "building", "name": "Barracks", "minute": 0.8},
            {"type": "building", "name": "CommandCenter", "minute": 2.0},
            {"type": "building", "name": "Barracks", "minute": 2.5},
            {"type": "building", "name": "Barracks", "minute": 3.0},
            {"type": "building", "name": "Factory", "minute": 3.5},
            {"type": "unit", "name": "Marine", "minute": 2.0},
        ]
        assert rule_based_label(build, "Terran") == "BIO_TIMING"

    def test_standard_111_with_factory(self):
        build = [
            {"type": "building", "name": "Barracks", "minute": 0.8},
            {"type": "building", "name": "Factory", "minute": 2.0},
            {"type": "building", "name": "Starport", "minute": 3.0},
        ]
        label = rule_based_label(build, "Terran")
        assert label is not None
        assert label != "RUSH"


class TestProtossLabelling:
    def test_cannon_rush(self):
        build = [
            {"type": "building", "name": "Forge", "minute": 0.8},
            {"type": "building", "name": "PhotonCannon", "minute": 2.5},
        ]
        assert rule_based_label(build, "Protoss") == "CANNON_RUSH"

    def test_nexus_first_is_macro(self):
        build = [
            {"type": "building", "name": "Nexus", "minute": 0.0},
            {"type": "building", "name": "Gateway", "minute": 0.7},
            {"type": "building", "name": "Nexus", "minute": 1.8},
            {"type": "building", "name": "CyberneticsCore", "minute": 2.0},
        ]
        assert rule_based_label(build, "Protoss") == "MACRO_ECONOMY"

    def test_twilight_is_blink(self):
        build = [
            {"type": "building", "name": "Gateway", "minute": 0.7},
            {"type": "building", "name": "CyberneticsCore", "minute": 1.5},
            {"type": "building", "name": "TwilightCouncil", "minute": 3.5},
        ]
        assert rule_based_label(build, "Protoss") == "BLINK_STALKER"

    def test_stargate_first_is_air(self):
        build = [
            {"type": "building", "name": "Gateway", "minute": 0.7},
            {"type": "building", "name": "CyberneticsCore", "minute": 1.5},
            {"type": "building", "name": "Stargate", "minute": 2.8},
        ]
        assert rule_based_label(build, "Protoss") == "AIR_SUPERIORITY"
