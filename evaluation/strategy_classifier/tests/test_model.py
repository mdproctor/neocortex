import torch
import pytest
from evaluation.strategy_classifier.model import StrategyClassifier, HierarchicalStrategyClassifier
from evaluation.strategy_classifier.config import HyperParams


from evaluation.strategy_classifier.sc2egset_extractor import N_FEATURES_PER_PLAYER
F_TEMPORAL = 2 * N_FEATURES_PER_PLAYER + 1  # player + opponent + has_vision
F_MAP = 6


class TestStrategyClassifier:
    def _make_model(self):
        hp = HyperParams()
        return StrategyClassifier(
            f_temporal=F_TEMPORAL, f_map=F_MAP, num_classes=8, hp=hp
        )

    def test_forward_shape(self):
        model = self._make_model()
        temporal = torch.randn(4, 10, F_TEMPORAL)
        map_feat = torch.randn(4, F_MAP)
        logits = model(temporal, map_feat)
        assert logits.shape == (4, 8)

    def test_single_sample(self):
        model = self._make_model()
        temporal = torch.randn(1, 10, F_TEMPORAL)
        map_feat = torch.randn(1, F_MAP)
        logits = model(temporal, map_feat)
        assert logits.shape == (1, 8)

    def test_output_is_finite(self):
        model = self._make_model()
        temporal = torch.randn(2, 10, F_TEMPORAL)
        map_feat = torch.randn(2, F_MAP)
        logits = model(temporal, map_feat)
        assert torch.isfinite(logits).all()

    def test_padding_mask_effect(self):
        model = self._make_model()
        model.eval()
        temporal = torch.randn(1, 10, F_TEMPORAL)
        temporal[0, 4:, :] = 0.0
        map_feat = torch.randn(1, F_MAP)
        logits_partial = model(temporal, map_feat)

        temporal2 = torch.randn(1, 10, F_TEMPORAL)
        map_feat2 = map_feat.clone()
        logits_full = model(temporal2, map_feat2)

        assert not torch.allclose(logits_partial, logits_full)

    def test_encode_shape(self):
        model = self._make_model()
        temporal = torch.randn(4, 10, F_TEMPORAL)
        map_feat = torch.randn(4, F_MAP)
        combined = model.encode(temporal, map_feat)
        expected_dim = 128 + F_MAP
        assert combined.shape == (4, expected_dim)

    def test_parameter_count(self):
        model = self._make_model()
        n_params = sum(p.numel() for p in model.parameters())
        assert n_params < 1_000_000


class TestHierarchicalStrategyClassifier:
    def _make_model(self):
        hp = HyperParams()
        base = StrategyClassifier(f_temporal=F_TEMPORAL, f_map=F_MAP, num_classes=5, hp=hp)
        fine_to_coarse = torch.tensor([0, 1, 2, 1, 2])
        return HierarchicalStrategyClassifier(base, num_coarse=3, fine_to_coarse=fine_to_coarse)

    def test_forward_returns_two_tensors(self):
        model = self._make_model()
        temporal = torch.randn(4, 10, F_TEMPORAL)
        map_feat = torch.randn(4, F_MAP)
        fine_logits, coarse_logits = model(temporal, map_feat)
        assert fine_logits.shape == (4, 5)
        assert coarse_logits.shape == (4, 3)

    def test_map_labels_to_coarse(self):
        model = self._make_model()
        fine_labels = torch.tensor([0, 1, 2, 3, 4])
        coarse_labels = model.map_labels_to_coarse(fine_labels)
        assert coarse_labels.tolist() == [0, 1, 2, 1, 2]

    def test_output_is_finite(self):
        model = self._make_model()
        temporal = torch.randn(2, 10, F_TEMPORAL)
        map_feat = torch.randn(2, F_MAP)
        fine_logits, coarse_logits = model(temporal, map_feat)
        assert torch.isfinite(fine_logits).all()
        assert torch.isfinite(coarse_logits).all()

    def test_base_model_unchanged_after_export(self):
        model = self._make_model()
        model.eval()
        temporal = torch.randn(1, 10, F_TEMPORAL)
        map_feat = torch.randn(1, F_MAP)
        with torch.no_grad():
            fine_logits, _ = model(temporal, map_feat)
            base_logits = model.base(temporal, map_feat)
        assert torch.allclose(fine_logits, base_logits)
