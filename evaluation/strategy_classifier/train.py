import torch
import torch.nn as nn
import torch.nn.functional as F
import numpy as np
from torch.utils.data import DataLoader
from torch.optim.lr_scheduler import CosineAnnealingLR
from evaluation.strategy_classifier.model import StrategyClassifier
from evaluation.strategy_classifier.config import HyperParams


class FocalLoss(nn.Module):
    def __init__(self, gamma: float = 2.0, weight: torch.Tensor = None,
                 label_smoothing: float = 0.0):
        super().__init__()
        self.gamma = gamma
        self.label_smoothing = label_smoothing
        self.register_buffer("weight", weight)

    def forward(self, logits: torch.Tensor, targets: torch.Tensor) -> torch.Tensor:
        ce = F.cross_entropy(logits, targets, weight=self.weight,
                             reduction="none", label_smoothing=self.label_smoothing)
        pt = torch.exp(-ce)
        return ((1 - pt) ** self.gamma * ce).mean()


def compute_class_weights(labels: np.ndarray, num_classes: int) -> torch.Tensor:
    counts = np.bincount(labels, minlength=num_classes).astype(np.float32)
    counts = np.maximum(counts, 1.0)
    weights = np.sqrt(counts.max() / counts)
    weights = np.clip(weights, 1.0, 5.0)
    return torch.from_numpy(weights)


def train_one_epoch(
    model: nn.Module, loader: DataLoader, optimizer, criterion,
    coarse_criterion=None, coarse_weight: float = 0.3,
) -> float:
    model.train()
    total_loss = 0.0
    n_batches = 0
    is_hierarchical = hasattr(model, "coarse_head")
    for temporal, map_feat, labels in loader:
        optimizer.zero_grad()
        if is_hierarchical:
            fine_logits, coarse_logits = model(temporal, map_feat)
            fine_loss = criterion(fine_logits, labels)
            coarse_labels = model.map_labels_to_coarse(labels)
            coarse_loss = coarse_criterion(coarse_logits, coarse_labels)
            loss = fine_loss + coarse_weight * coarse_loss
        else:
            logits = model(temporal, map_feat)
            loss = criterion(logits, labels)
        loss.backward()
        optimizer.step()
        total_loss += loss.item()
        n_batches += 1
    return total_loss / max(n_batches, 1)


@torch.no_grad()
def evaluate(model: nn.Module, loader: DataLoader) -> tuple:
    model.eval()
    all_logits, all_labels = [], []
    is_hierarchical = hasattr(model, "coarse_head")
    for temporal, map_feat, labels in loader:
        output = model(temporal, map_feat)
        logits = output[0] if is_hierarchical else output
        all_logits.append(logits)
        all_labels.append(labels)
    logits = torch.cat(all_logits)
    labels = torch.cat(all_labels)
    preds = logits.argmax(dim=-1)
    acc = (preds == labels).float().mean().item()
    loss = F.cross_entropy(logits, labels).item()
    return loss, acc, logits, labels


def find_optimal_temperature(logits: torch.Tensor, labels: torch.Tensor) -> float:
    temperature = nn.Parameter(torch.ones(1))
    optimizer = torch.optim.LBFGS([temperature], lr=0.01, max_iter=50)

    def closure():
        optimizer.zero_grad()
        loss = F.cross_entropy(logits / temperature, labels)
        loss.backward()
        return loss

    optimizer.step(closure)
    return temperature.item()


def bake_temperature(model: nn.Module, temperature: float):
    final_layer = model.classifier[-1]
    with torch.no_grad():
        final_layer.weight.div_(temperature)
        final_layer.bias.div_(temperature)


def train_model(
    model,
    train_loader: DataLoader,
    val_loader: DataLoader,
    hp: HyperParams,
    class_weights: torch.Tensor = None,
    coarse_weight: float = 0.3,
) -> dict:
    torch.manual_seed(hp.seed)
    optimizer = torch.optim.AdamW(model.parameters(), lr=hp.lr)
    scheduler = CosineAnnealingLR(optimizer, T_max=hp.max_epochs)
    criterion = FocalLoss(gamma=hp.focal_gamma, weight=class_weights,
                          label_smoothing=0.1)
    is_hierarchical = hasattr(model, "coarse_head")
    coarse_criterion = FocalLoss(gamma=hp.focal_gamma) if is_hierarchical else None

    best_val_loss = float("inf")
    patience_counter = 0
    best_state = None
    history = {"train_loss": [], "val_loss": [], "val_acc": []}

    for epoch in range(hp.max_epochs):
        train_loss = train_one_epoch(
            model, train_loader, optimizer, criterion,
            coarse_criterion=coarse_criterion, coarse_weight=coarse_weight,
        )
        val_loss, val_acc, _, _ = evaluate(model, val_loader)
        scheduler.step()

        history["train_loss"].append(train_loss)
        history["val_loss"].append(val_loss)
        history["val_acc"].append(val_acc)

        print(f"Epoch {epoch+1}: train_loss={train_loss:.4f} "
              f"val_loss={val_loss:.4f} val_acc={val_acc:.4f}")

        if val_loss < best_val_loss:
            best_val_loss = val_loss
            patience_counter = 0
            best_state = {k: v.clone() for k, v in model.state_dict().items()}
        else:
            patience_counter += 1
            if patience_counter >= hp.patience:
                print(f"Early stopping at epoch {epoch+1}")
                break

    if best_state:
        model.load_state_dict(best_state)

    return history
