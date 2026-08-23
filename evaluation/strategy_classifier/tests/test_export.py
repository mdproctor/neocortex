import torch
import tempfile
import onnxruntime as ort
import numpy as np
import pytest
from pathlib import Path
from evaluation.strategy_classifier.model import StrategyClassifier
from evaluation.strategy_classifier.export_onnx import export_to_onnx
from evaluation.strategy_classifier.config import HyperParams
from evaluation.strategy_classifier.sc2egset_extractor import N_FEATURES_PER_PLAYER as N_PLAYER


class TestOnnxExport:
    def test_export_and_load(self):
        hp = HyperParams()
        model = StrategyClassifier(f_temporal=2*N_PLAYER+1, f_map=6, num_classes=8, hp=hp)
        model.eval()

        with tempfile.TemporaryDirectory() as tmpdir:
            path = export_to_onnx(
                model, f_temporal=2*N_PLAYER+1, f_map=6,
                matchup="vs_terran", output_dir=Path(tmpdir),
                max_windows=hp.max_windows,
            )
            assert path.exists()
            assert path.stat().st_size < 10 * 1024 * 1024

            sess = ort.InferenceSession(str(path))
            inputs = sess.get_inputs()
            assert len(inputs) == 2
            assert inputs[0].name == "temporal"
            assert inputs[1].name == "map"

    def test_onnx_output_matches_pytorch(self):
        hp = HyperParams()
        model = StrategyClassifier(f_temporal=2*N_PLAYER+1, f_map=6, num_classes=8, hp=hp)
        model.eval()

        temporal = torch.randn(1, hp.max_windows, 2*N_PLAYER+1)
        map_feat = torch.randn(1, 6)

        with torch.no_grad():
            pt_out = model(temporal, map_feat).numpy()

        with tempfile.TemporaryDirectory() as tmpdir:
            path = export_to_onnx(
                model, f_temporal=2*N_PLAYER+1, f_map=6,
                matchup="vs_terran", output_dir=Path(tmpdir),
                max_windows=hp.max_windows,
            )
            sess = ort.InferenceSession(str(path))

            temporal_flat = temporal.reshape(1, -1).numpy()
            map_np = map_feat.numpy()

            ort_out = sess.run(None, {
                "temporal": temporal_flat,
                "map": map_np,
            })[0]

            np.testing.assert_allclose(pt_out, ort_out, atol=1e-5)
