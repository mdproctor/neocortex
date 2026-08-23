"""Extract training features from SC2EGSet JSON replays.

Reads the nested-ZIP JSON format (same as IEM10 data), extracts per-player
game state over time, and produces feature arrays compatible with the
strategy classifier pipeline.

Usage: python3 -m evaluation.strategy_classifier.sc2egset_extractor --zip <path.zip>
"""
import json
import io
import zipfile
import numpy as np
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from dataclasses import dataclass, field
from evaluation.strategy_classifier.config import HyperParams, Paths
from evaluation.strategy_classifier.feature_engineering import (
    build_temporal_features, build_map_tensor, F_MAP,
)
from evaluation.strategy_classifier.fog_of_war import generate_scouting_mask
from evaluation.strategy_classifier.dataset import per_replay_split

LOOPS_PER_SECOND = 22.4

BUILDINGS = [
    "CommandCenter", "OrbitalCommand", "PlanetaryFortress",
    "Barracks", "Factory", "Starport",
    "EngineeringBay", "Armory", "GhostAcademy", "FusionCore",
    "Bunker", "MissileTurret", "SensorTower",
    "SupplyDepot", "Refinery",
    "BarracksReactor", "BarracksTechLab",
    "FactoryReactor", "FactoryTechLab",
    "StarportReactor", "StarportTechLab",
    "Hatchery", "Lair", "Hive",
    "SpawningPool", "BanelingNest", "RoachWarren",
    "EvolutionChamber", "Extractor", "HydraliskDen",
    "SpineCrawler", "SporeCrawler", "Spire", "GreaterSpire",
    "InfestationPit", "NydusNetwork", "UltraliskCavern",
    "Nexus", "Gateway", "WarpGate", "CyberneticsCore",
    "Forge", "Assimilator", "Pylon", "PhotonCannon", "ShieldBattery",
    "RoboticsFacility", "RoboticsBay",
    "Stargate", "FleetBeacon",
    "TwilightCouncil", "TemplarArchive", "DarkShrine",
]

UNITS = [
    "SCV", "Marine", "Marauder", "Reaper", "Ghost",
    "Hellion", "HellionTank", "SiegeTank", "Cyclone", "Thor",
    "Medivac", "VikingFighter", "VikingAssault", "Liberator", "Banshee", "Raven", "WidowMine",
    "Drone", "Zergling", "Baneling", "Roach", "Ravager",
    "Queen", "Mutalisk", "Corruptor", "BroodLord",
    "Hydralisk", "Lurker", "Infestor", "SwarmHost", "Ultralisk", "Viper",
    "Overlord", "Overseer",
    "Probe", "Zealot", "Stalker", "Sentry", "Adept",
    "HighTemplar", "DarkTemplar", "Archon",
    "Immortal", "Colossus", "Disruptor", "WarpPrism",
    "Phoenix", "Oracle", "VoidRay", "Carrier", "Tempest", "Mothership",
    "Observer",
]

STAT_KEYS = [
    "scoreValueMineralsCurrent", "scoreValueVespeneCurrent",
    "scoreValueMineralsCollectionRate", "scoreValueVespeneCollectionRate",
    "scoreValueFoodMade", "scoreValueFoodUsed",
    "scoreValueWorkersActiveCount",
    "scoreValueMineralsUsedCurrentArmy", "scoreValueMineralsUsedCurrentEconomy",
    "scoreValueMineralsUsedCurrentTechnology",
    "scoreValueVespeneUsedCurrentArmy", "scoreValueVespeneUsedCurrentEconomy",
    "scoreValueVespeneUsedCurrentTechnology",
]

UPGRADES = [
    "Stimpack", "ShieldWall", "PunisherGrenades", "BansheeCloak",
    "TerranVehicleWeaponsLevel1", "PersonalCloaking", "DrillClaws",
    "zerglingmovementspeed", "GlialReconstitution", "CentrificalHooks",
    "Burrow", "WarpGateResearch", "BlinkTech", "Charge",
    "AdeptPiercingAttack",
]

BUILDING_IDX = {b: i for i, b in enumerate(BUILDINGS)}
UNIT_IDX = {u: i for i, u in enumerate(UNITS)}
UPGRADE_IDX = {u: i for i, u in enumerate(UPGRADES)}

N_BUILDINGS = len(BUILDINGS)
N_UNITS = len(UNITS)
N_STATS = len(STAT_KEYS)
N_UPGRADES = len(UPGRADES)
N_FEATURES_PER_PLAYER = N_BUILDINGS + N_UNITS + N_STATS + N_UPGRADES

RACE_MAP = {"Terr": "Terran", "Prot": "Protoss", "Zerg": "Zerg"}
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


@dataclass
class ReplayData:
    map_name: str
    matchup: str
    player1_race: str
    player2_race: str
    winner: int
    duration_seconds: int
    player1_features: np.ndarray
    player2_features: np.ndarray


def _loop_to_second(loop: int) -> int:
    return int(loop / LOOPS_PER_SECOND)


def extract_replay(game_json: dict) -> Optional[ReplayData]:
    """Extract per-second feature arrays from a single SC2EGSet JSON replay."""
    meta = game_json.get("metadata", {})
    map_name = meta.get("mapName", "Unknown")

    toon_map = game_json.get("ToonPlayerDescMap", {})
    if len(toon_map) != 2:
        return None

    players = {}
    for toon, desc in toon_map.items():
        pid = desc["playerID"]
        race = RACE_MAP.get(desc.get("race", ""), desc.get("race", ""))
        result = desc.get("result", "")
        players[pid] = {"race": race, "result": result}

    if 1 not in players or 2 not in players:
        return None

    p1_race = players[1]["race"]
    p2_race = players[2]["race"]
    matchup_key = (p1_race, p2_race)
    if matchup_key not in MATCHUP_MAP:
        return None
    matchup = MATCHUP_MAP[matchup_key]

    winner = 1 if players[1]["result"] == "Win" else 2

    header = game_json.get("header", {})
    total_loops = header.get("elapsedGameLoops", 0)
    duration_seconds = max(_loop_to_second(total_loops), 1)
    duration_seconds = min(duration_seconds, 600)

    p1_features = np.zeros((duration_seconds, N_FEATURES_PER_PLAYER), dtype=np.float32)
    p2_features = np.zeros((duration_seconds, N_FEATURES_PER_PLAYER), dtype=np.float32)

    building_counts = {1: np.zeros(N_BUILDINGS, dtype=np.float32),
                       2: np.zeros(N_BUILDINGS, dtype=np.float32)}
    unit_counts = {1: np.zeros(N_UNITS, dtype=np.float32),
                   2: np.zeros(N_UNITS, dtype=np.float32)}
    last_stats = {1: np.zeros(N_STATS, dtype=np.float32),
                  2: np.zeros(N_STATS, dtype=np.float32)}
    upgrade_flags = {1: np.zeros(N_UPGRADES, dtype=np.float32),
                     2: np.zeros(N_UPGRADES, dtype=np.float32)}

    unit_tag_to_type = {}

    tracker = game_json.get("trackerEvents", [])
    event_idx = 0

    for sec in range(duration_seconds):
        sec_loop = int(sec * LOOPS_PER_SECOND)

        while event_idx < len(tracker):
            e = tracker[event_idx]
            e_loop = e.get("loop", 0)
            if e_loop > sec_loop + LOOPS_PER_SECOND:
                break
            event_idx += 1

            etype = e.get("evtTypeName", "")
            pid = e.get("controlPlayerId", e.get("playerId", 0))
            if pid not in (1, 2):
                continue

            if etype in ("UnitInit", "UnitDone"):
                unit_name = e.get("unitTypeName", "")
                tag = (e.get("unitTagIndex", 0), e.get("unitTagRecycle", 0))
                if etype == "UnitInit":
                    unit_tag_to_type[tag] = (unit_name, pid)
                if unit_name in BUILDING_IDX:
                    building_counts[pid][BUILDING_IDX[unit_name]] += 1

            elif etype == "UnitBorn":
                unit_name = e.get("unitTypeName", "")
                tag = (e.get("unitTagIndex", 0), e.get("unitTagRecycle", 0))
                unit_tag_to_type[tag] = (unit_name, pid)
                if unit_name in UNIT_IDX:
                    unit_counts[pid][UNIT_IDX[unit_name]] += 1

            elif etype == "UnitDied":
                tag = (e.get("unitTagIndex", 0), e.get("unitTagRecycle", 0))
                if tag in unit_tag_to_type:
                    unit_name, owner = unit_tag_to_type[tag]
                    if unit_name in BUILDING_IDX:
                        building_counts[owner][BUILDING_IDX[unit_name]] = max(
                            0, building_counts[owner][BUILDING_IDX[unit_name]] - 1)
                    if unit_name in UNIT_IDX:
                        unit_counts[owner][UNIT_IDX[unit_name]] = max(
                            0, unit_counts[owner][UNIT_IDX[unit_name]] - 1)

            elif etype == "Upgrade":
                upgrade_name = e.get("upgradeTypeName", "")
                if upgrade_name in UPGRADE_IDX:
                    upgrade_flags[pid][UPGRADE_IDX[upgrade_name]] = 1.0

            elif etype == "PlayerStats":
                stats = e.get("stats", {})
                for i, key in enumerate(STAT_KEYS):
                    val = stats.get(key, 0)
                    last_stats[pid][i] = float(val) / 1000.0

        for pid, features in [(1, p1_features), (2, p2_features)]:
            features[sec, :N_BUILDINGS] = building_counts[pid]
            features[sec, N_BUILDINGS:N_BUILDINGS + N_UNITS] = unit_counts[pid]
            features[sec, N_BUILDINGS + N_UNITS:N_BUILDINGS + N_UNITS + N_STATS] = last_stats[pid]
            features[sec, N_BUILDINGS + N_UNITS + N_STATS:] = upgrade_flags[pid]

    return ReplayData(
        map_name=map_name, matchup=matchup,
        player1_race=p1_race, player2_race=p2_race,
        winner=winner, duration_seconds=duration_seconds,
        player1_features=p1_features, player2_features=p2_features,
    )


def extract_from_zip(
    zip_path: Path, hp: HyperParams = HyperParams(),
) -> Dict[str, List[Tuple[np.ndarray, np.ndarray, str, ReplayData]]]:
    """Extract features from all replays in an SC2EGSet ZIP.

    Returns dict[matchup] -> list of (temporal, map_feat, replay_data) per minute.
    Each replay produces samples from both players' perspectives.
    """
    outer = zipfile.ZipFile(zip_path)
    data_zip_name = None
    for name in outer.namelist():
        if name.endswith("_data.zip"):
            data_zip_name = name
            break

    if data_zip_name is None:
        json_files = [n for n in outer.namelist() if n.endswith(".json") and "mapping" not in n and "summary" not in n]
        if not json_files:
            raise ValueError(f"No data found in {zip_path}")
        inner = outer
    else:
        inner_bytes = outer.read(data_zip_name)
        inner = zipfile.ZipFile(io.BytesIO(inner_bytes))

    results: Dict[str, list] = {}
    n_extracted = 0
    n_failed = 0

    json_files = [n for n in inner.namelist() if n.endswith(".SC2Replay.json")]
    print(f"Found {len(json_files)} replays in {zip_path.name}")

    for name in json_files:
        try:
            game = json.loads(inner.read(name))
            replay = extract_replay(game)
            if replay is None:
                n_failed += 1
                continue

            if replay.matchup not in results:
                results[replay.matchup] = []

            results[replay.matchup].append(replay)
            n_extracted += 1
        except Exception as e:
            n_failed += 1

    print(f"Extracted {n_extracted} replays, {n_failed} failed/skipped")
    for matchup, replays in sorted(results.items()):
        print(f"  {matchup}: {len(replays)} replays")

    return results


def build_samples_from_replays(
    replays: List[ReplayData], hp: HyperParams, rng: np.random.Generator,
    minutes: List[int] = None,
) -> Tuple[List[Tuple[np.ndarray, np.ndarray, int]], List[int], List[int]]:
    """Convert extracted replays into windowed training samples.

    For each replay, both players produce samples (perspective swap).
    Labels are placeholder (-1) — labelling is a separate step.
    """
    if minutes is None:
        minutes = [2, 3, 4, 5]

    samples = []
    replay_ids = []
    replay_labels = []
    replay_id = 0

    for replay in replays:
        for perspective_player, features, opponent_features in [
            (1, replay.player1_features, replay.player2_features),
            (2, replay.player2_features, replay.player1_features),
        ]:
            map_tensor = build_map_tensor(replay.map_name)
            mask = generate_scouting_mask(replay.duration_seconds, rng)

            for minute in minutes:
                if minute * 60 > replay.duration_seconds:
                    continue
                temporal = build_temporal_features(
                    features, opponent_features, mask, minute, hp
                )
                samples.append((temporal, map_tensor, -1))

            replay_ids.append(replay_id)
            replay_labels.append(0)
            replay_id += 1

    return samples, replay_ids, replay_labels


if __name__ == "__main__":
    import sys
    if len(sys.argv) < 3 or sys.argv[1] != "--zip":
        print("Usage: python3 -m evaluation.strategy_classifier.sc2egset_extractor --zip <path.zip>")
        sys.exit(1)

    zip_path = Path(sys.argv[2])
    results = extract_from_zip(zip_path)

    total = sum(len(v) for v in results.values())
    print(f"\nTotal: {total} replays extracted across {len(results)} matchups")
    print(f"Features per player: {N_FEATURES_PER_PLAYER} ({N_BUILDINGS} buildings + {N_UNITS} units + {N_STATS} stats)")
