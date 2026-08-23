import math
import torch
import torch.nn as nn
from evaluation.strategy_classifier.config import HyperParams

from evaluation.strategy_classifier.sc2egset_extractor import N_FEATURES_PER_PLAYER
N_PLAYER_FEATURES = N_FEATURES_PER_PLAYER


class ConvEncoder(nn.Module):
    def __init__(self, f_in: int, d_proj: int, c1: int, c2: int, max_windows: int):
        super().__init__()
        self.proj = nn.Linear(f_in, d_proj)
        self.conv1 = nn.Conv1d(d_proj, c1, kernel_size=3, padding=1)
        self.ln1 = nn.LayerNorm([c1, max_windows])
        self.conv2 = nn.Conv1d(c1, c2, kernel_size=3, padding=1)
        self.ln2 = nn.LayerNorm([c2, max_windows])

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = torch.relu(self.proj(x))
        x = x.permute(0, 2, 1)
        x = torch.relu(self.ln1(self.conv1(x)))
        x = torch.relu(self.ln2(self.conv2(x)))
        return x.permute(0, 2, 1)


class StrategyClassifier(nn.Module):
    def __init__(self, f_temporal: int, f_map: int, num_classes: int, hp: HyperParams):
        super().__init__()
        c1, c2 = hp.conv_channels
        f_opponent = f_temporal - N_PLAYER_FEATURES
        d_proj = c1

        self.player_enc = ConvEncoder(N_PLAYER_FEATURES, d_proj, c1, c2, hp.max_windows)
        self.opponent_enc = ConvEncoder(f_opponent, d_proj, c1, c2, hp.max_windows)
        self.gate = nn.Linear(2, 2)

        self.pos_encoding = SinusoidalPositionalEncoding(c2, hp.max_windows)

        self.attn_norm = nn.LayerNorm(c2)
        self.attn = nn.MultiheadAttention(
            embed_dim=c2, num_heads=4, batch_first=True, dropout=hp.dropout
        )

        self.classifier = nn.Sequential(
            nn.Linear(c2 + f_map, hp.dense_hidden),
            nn.ReLU(),
            nn.Dropout(hp.dropout),
            nn.Linear(hp.dense_hidden, num_classes),
        )

    def encode(self, temporal: torch.Tensor, map_feat: torch.Tensor) -> torch.Tensor:
        player = temporal[:, :, :N_PLAYER_FEATURES]
        opponent = temporal[:, :, N_PLAYER_FEATURES:]

        avail = map_feat[:, 4:6]
        g = torch.sigmoid(self.gate(avail))
        g_p = g[:, 0:1].unsqueeze(1)
        g_o = g[:, 1:2].unsqueeze(1)

        p = self.player_enc(player)
        o = self.opponent_enc(opponent)

        x = g_p * p + g_o * o

        padding_mask = (temporal.abs().sum(dim=-1) == 0)

        x = self.pos_encoding(x)

        residual = x
        x, _ = self.attn(x, x, x, key_padding_mask=padding_mask)
        x = self.attn_norm(x + residual)

        valid_mask = (~padding_mask).unsqueeze(-1).float()
        valid_count = valid_mask.sum(dim=1).clamp(min=1)
        pooled = (x * valid_mask).sum(dim=1) / valid_count

        return torch.cat([pooled, map_feat], dim=-1)

    def forward(self, temporal: torch.Tensor, map_feat: torch.Tensor) -> torch.Tensor:
        combined = self.encode(temporal, map_feat)
        return self.classifier(combined)


class HierarchicalStrategyClassifier(nn.Module):
    def __init__(self, base: StrategyClassifier, num_coarse: int,
                 fine_to_coarse: torch.Tensor):
        super().__init__()
        self.base = base
        combined_dim = base.classifier[0].in_features
        self.coarse_head = nn.Linear(combined_dim, num_coarse)
        self.register_buffer("fine_to_coarse", fine_to_coarse)

    def forward(self, temporal: torch.Tensor, map_feat: torch.Tensor):
        combined = self.base.encode(temporal, map_feat)
        fine_logits = self.base.classifier(combined)
        coarse_logits = self.coarse_head(combined)
        return fine_logits, coarse_logits

    def map_labels_to_coarse(self, fine_labels: torch.Tensor) -> torch.Tensor:
        return self.fine_to_coarse[fine_labels]


class SinusoidalPositionalEncoding(nn.Module):
    def __init__(self, d_model: int, max_len: int):
        super().__init__()
        pe = torch.zeros(max_len, d_model)
        position = torch.arange(0, max_len, dtype=torch.float).unsqueeze(1)
        div_term = torch.exp(
            torch.arange(0, d_model, 2).float() * (-math.log(10000.0) / d_model)
        )
        pe[:, 0::2] = torch.sin(position * div_term)
        pe[:, 1::2] = torch.cos(position * div_term)
        self.register_buffer("pe", pe.unsqueeze(0))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return x + self.pe[:, :x.size(1), :]
