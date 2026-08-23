import torch
import numpy as np
import pytest
from evaluation.strategy_classifier.model import StrategyClassifier, HierarchicalStrategyClassifier
from evaluation.strategy_classifier.sc2egset_extractor import N_FEATURES_PER_PLAYER as N_PLAYER
from evaluation.strategy_classifier.train import (
    FocalLoss, train_one_epoch, find_optimal_temperature,
)
from evaluation.strategy_classifier.config import HyperParams
from evaluation.strategy_classifier.dataset import StrategyDataset
from torch.utils.data import DataLoader


class TestFocalLoss:
    def test_reduces_to_ce_at_gamma_zero(self):
        focal = FocalLoss(gamma=0.0)
        ce = torch.nn.CrossEntropyLoss()
        logits = torch.randn(8, 5)
        targets = torch.randint(0, 5, (8,))
        assert torch.allclose(focal(logits, targets), ce(logits, targets), atol=1e-5)

    def test_gradient_flows(self):
        focal = FocalLoss(gamma=2.0)
        logits = torch.randn(4, 3, requires_grad=True)
        targets = torch.tensor([0, 1, 2, 0])
        loss = focal(logits, targets)
        loss.backward()
        assert logits.grad is not None


class TestTrainOneEpoch:
    def _make_loader(self):
        samples = [
            (np.random.rand(10, 2*N_PLAYER+1).astype(np.float32),
             np.random.rand(6).astype(np.float32),
             i % 3)
            for i in range(32)
        ]
        return DataLoader(StrategyDataset(samples), batch_size=8)

    def test_loss_is_finite(self):
        hp = HyperParams()
        model = StrategyClassifier(f_temporal=2*N_PLAYER+1, f_map=6, num_classes=3, hp=hp)
        loader = self._make_loader()
        optimizer = torch.optim.AdamW(model.parameters(), lr=hp.lr)
        loss = train_one_epoch(model, loader, optimizer, FocalLoss(gamma=2.0))
        assert np.isfinite(loss)


class TestHierarchicalTraining:
    def _make_loader(self):
        samples = [
            (np.random.rand(10, 2*N_PLAYER+1).astype(np.float32),
             np.random.rand(6).astype(np.float32),
             i % 5)
            for i in range(32)
        ]
        return DataLoader(StrategyDataset(samples), batch_size=8)

    def test_hierarchical_loss_is_finite(self):
        hp = HyperParams()
        base = StrategyClassifier(f_temporal=2*N_PLAYER+1, f_map=6, num_classes=5, hp=hp)
        fine_to_coarse = torch.tensor([0, 1, 2, 1, 2])
        model = HierarchicalStrategyClassifier(base, num_coarse=3, fine_to_coarse=fine_to_coarse)
        loader = self._make_loader()
        optimizer = torch.optim.AdamW(model.parameters(), lr=hp.lr)
        fine_criterion = FocalLoss(gamma=2.0)
        coarse_criterion = FocalLoss(gamma=2.0)
        loss = train_one_epoch(model, loader, optimizer, fine_criterion,
                               coarse_criterion=coarse_criterion, coarse_weight=0.3)
        assert np.isfinite(loss)


class TestTemperatureCalibration:
    def test_optimal_temperature_is_positive(self):
        logits = torch.randn(100, 5)
        labels = torch.randint(0, 5, (100,))
        t = find_optimal_temperature(logits, labels)
        assert t > 0
