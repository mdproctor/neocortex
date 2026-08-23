# Strategy Classifier Training Data

This directory is gitignored — data files are not committed to the repository.
Training data and models are published to GitHub Releases (#209) for persistence.

## Data Retention Policy

**Raw downloads are permanent. Never delete them.**

Raw data lives in source-specific subdirectories and must be retained across
reprocessing runs. Processed NPZ files are derived — they can always be
regenerated from the raw data by re-running the adapters.

| Type | Location | Retention |
|------|----------|-----------|
| Raw downloads | `sc2egset/raw/`, `spawningtool/replays/`, `msc/parsed_replays/` | **Permanent** — never delete |
| Per-source NPZ | `sc2egset/*.npz`, `spawningtool/*.npz`, `msc/*.npz` | Derived — regenerable from raw |
| Combined NPZ | `combined/*.npz` | Derived — regenerable from per-source |
| Extracted replays | `spawningtool/replays/extracted/` | Derived — regenerable from ZIPs |

When reprocessing (e.g. after fixing labels or adding features), the adapters
overwrite per-source NPZ files. This is safe because the raw data is untouched.

## Data Sources

| Source | Raw Location | Status | Notes |
|--------|-------------|--------|-------|
| SC2EGSet | `sc2egset/raw/*.zip` | **Complete** — 71 tournament ZIPs downloaded | From [Zenodo](https://zenodo.org/records/14963484) |
| MSC | `msc/parsed_replays/` | Complete | Downloaded via `download_msc.py` |
| Spawning Tool | `spawningtool/replays/*.zip` | Complete | 4 tournament replay packs |

## Processing Pipeline

```
Raw data → per-source adapter → per-source NPZ → normalize.py → combined NPZ → run_pipeline.py
```

### Per-Source Adapters

| Source | Adapter | Features | Upgrades | Labels |
|--------|---------|----------|----------|--------|
| SC2EGSet | `prepare_real_data.py --zips <paths>` | Player + Opponent | Yes (15 tracked) | Fixed (MACRO_ECONOMY fallback) |
| MSC | `ingest_msc.py` | Player only | No | Fixed |
| Spawning Tool | `ingest_spawningtool.py --dir <path>` | Player + Opponent | Player only | Fixed |

### Feature Vector

269 features per temporal window = 2 x 134 (per player) + 1 (has_vision flag)

Per player: 55 buildings + 51 units + 13 economy stats + 15 upgrades = 134

### Processed Data

| Directory | Contents |
|-----------|----------|
| `sc2egset/<tournament>/` | Per-tournament NPZ (unnormalized, pre-consolidation labels) |
| `msc/` | Per-source NPZ |
| `spawningtool/` | Per-source NPZ + raw replay ZIPs |
| `combined/` | Merged, normalized, consolidated NPZ + `classes.json` + `norm_stats.npz` |
| `synthetic/` | Legacy synthetic data (superseded by real data) |

Per-tournament layout: `sc2egset/<tournament_name>/vs_terran/train.npz` etc.
Resume support: `prepare_real_data.py` skips ZIPs whose tournament directory
already exists. Use `--force` to reprocess.

### SC2EGSet Download Status

All 71 tournament ZIPs downloaded in `sc2egset/raw/` (~12GB).

To process all ZIPs (resumes from where it left off):
```bash
# Process ZIPs one at a time into per-tournament dirs (OOM-safe, resumable)
python3 -m evaluation.strategy_classifier.prepare_real_data --zips evaluation/strategy_classifier/data/sc2egset/raw/*.zip

# Re-normalize across all sources
python3 -m evaluation.strategy_classifier.normalize --sources sc2egset spawningtool msc --min-samples 350
```

## Current Model Accuracy (2026-08-21)

| Matchup | Top-1 | Target | Classes |
|---------|-------|--------|---------|
| vs_terran | 55.3% | 65% | RUSH, BANSHEE_HARASS, MECH_PUSH, BIO_TIMING |
| vs_zerg | 66.5% | 65% | RUSH, ROACH_RUSH, LING_BANE, MACRO_ECONOMY |
| vs_protoss | ~72% | 65% | 8 classes |
