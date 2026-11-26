/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.neocortex.cognitive.index;

import io.casehub.neocortex.cognitive.TenantAgentKey;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class EffectiveCognitiveDefaults {

    private final CognitiveDefaultsRegistry explicitLayer;
    private final DescriptorViewSource viewSource;
    private final PersonalityStateSource stateSource;
    private final ConcurrentHashMap<TenantAgentKey, Slot> slots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TenantAgentKey, AtomicLong> baseVersions = new ConcurrentHashMap<>();
    private volatile java.util.function.Function<String, io.casehub.neocortex.cognitive.observability.DerivationObserver> derivationObserverFactory =
            tenantId -> io.casehub.neocortex.cognitive.observability.DerivationObserver.NOOP;

    public EffectiveCognitiveDefaults(CognitiveDefaultsRegistry explicitLayer,
                                       DescriptorViewSource viewSource,
                                       PersonalityStateSource stateSource) {
        this.explicitLayer = explicitLayer;
        this.viewSource = viewSource;
        this.stateSource = stateSource;
    }

    public void setDerivationObserverFactory(java.util.function.Function<String, io.casehub.neocortex.cognitive.observability.DerivationObserver> factory) {
        this.derivationObserverFactory = factory;
    }

    public CognitiveDefaults forAgent(String tenantId, String agentId) {
        var key = new TenantAgentKey(tenantId, agentId);
        var slot = slots.get(key);
        if (slot != null && !slot.isStale(currentBaseVersion(key), currentCompositionVersion(key))) {
            return slot.effective;
        }
        return ensureSlot(key).effective;
    }

    public CognitiveDefaults forAgentOrDefaults(String tenantId, String agentId) {
        var defaults = forAgent(tenantId, agentId);
        return defaults != null ? defaults : CognitiveDefaults.empty(agentId);
    }

    public LearningProfile restingFor(String tenantId, String agentId) {
        var key = new TenantAgentKey(tenantId, agentId);
        var slot = slots.get(key);
        if (slot != null && !slot.isStale(currentBaseVersion(key), currentCompositionVersion(key))) {
            return slot.resting;
        }
        return ensureSlot(key).resting;
    }

    public void updateBase(String tenantId, DescriptorView view, String eventRef) {
        var key = new TenantAgentKey(tenantId, view.agentId());
        incrementBaseVersion(key);
        recompute(key, view, eventRef);
    }

    public Set<String> agentIds(String tenantId) {
        var ids = new LinkedHashSet<>(viewSource.agentIds(tenantId));
        ids.addAll(explicitLayer.allAgentIds());
        return Set.copyOf(ids);
    }

    public void evictIdle(Duration maxIdle) {
        var cutoff = Instant.now().minus(maxIdle);
        slots.entrySet().removeIf(e -> e.getValue().lastAccess.isBefore(cutoff));
    }

    public void refreshStale(Duration maxAge) {
        var cutoff = Instant.now().minus(maxAge);
        for (var entry : slots.entrySet()) {
            if (entry.getValue().lastAccess.isBefore(cutoff)) {
                incrementBaseVersion(entry.getKey());
                ensureSlot(entry.getKey());
            }
        }
    }


    public void onExplicitReload() {
        for (var key : slots.keySet()) {
            incrementBaseVersion(key);
            recompute(key, null, "reload");
        }
    }

    public void invalidateBase(String tenantId, String agentId) {
        incrementBaseVersion(new TenantAgentKey(tenantId, agentId));
    }


    public static EffectiveCognitiveDefaults forTesting(CognitiveDefaults... profiles) {
        var registry = CognitiveDefaultsRegistry.forTesting(profiles);
        var viewSource = new DescriptorViewSource() {
            @Override public java.util.Optional<DescriptorView> find(String t, String a) {
                return registry.forAgent(a).map(CognitiveDefaults::descriptor);
            }
            @Override public java.util.List<String> agentIds(String t) {
                return java.util.List.copyOf(registry.allAgentIds());
            }
        };
        var stateSource = new PersonalityStateSource() {
            @Override public java.util.Optional<PersonalityStateSnapshot> find(String t, String a, Instant now) {
                return java.util.Optional.empty();
            }
        };
        return new EffectiveCognitiveDefaults(registry, viewSource, stateSource);
    }

    // --- internals ---

    private Slot ensureSlot(TenantAgentKey key) {
        var baseView = viewSource.find(key.tenantId(), key.agentId()).orElse(null);
        var stateSnap = stateSource.find(key.tenantId(), key.agentId(), Instant.now()).orElse(null);
        long bv = currentBaseVersion(key);
        long sv = stateSnap != null ? stateSnap.stateVersion() : 0;
        long cv = currentCompositionVersion(key);

        var candidate = deriveSlot(key, baseView, stateSnap, bv, sv, cv);
        return slots.compute(key, (k, existing) -> {
            if (existing == null) return candidate;
            if (candidate.baseVersion > existing.baseVersion) return candidate;
            if (candidate.baseVersion == existing.baseVersion
                    && candidate.compositionVersion > existing.compositionVersion) return candidate;
            return existing;
        });
    }

    private void recompute(TenantAgentKey key, DescriptorView newBase, String eventRef) {
        var existing = slots.get(key);
        var baseView = newBase;
        if (baseView == null && existing != null) {
            baseView = existing.baseView;
        }
        if (baseView == null) {
            baseView = viewSource.find(key.tenantId(), key.agentId()).orElse(null);
        }

        var stateSnap = stateSource.find(key.tenantId(), key.agentId(), Instant.now()).orElse(null);

        long bv = currentBaseVersion(key);
        long sv = stateSnap != null ? stateSnap.stateVersion() : 0;
        long cv = currentCompositionVersion(key);

        var candidate = deriveSlot(key, baseView, stateSnap, bv, sv, cv);
        slots.compute(key, (k, prev) -> {
            if (prev == null) return candidate;
            if (candidate.baseVersion > prev.baseVersion) return candidate;
            if (candidate.baseVersion == prev.baseVersion
                    && candidate.compositionVersion > prev.compositionVersion) return candidate;
            return prev;
        });
    }

    private Slot deriveSlot(TenantAgentKey key, DescriptorView baseView,
                            PersonalityStateSnapshot state, long bv, long sv, long cv) {
        var explicit = explicitLayer.forAgent(key.agentId()).orElse(null);

        DescriptorView restingView = baseView;
        if (baseView != null && state != null && !state.facets().isEmpty()) {
            restingView = LivedComposition.compose(baseView, state, false);
        }
        var observer = derivationObserverFactory.apply(key.tenantId());
        CognitiveDefaults restingDefaults = restingView != null
                ? merge(explicit, CognitiveDerivationEngine.derive(restingView, observer))
                : (explicit != null ? explicit : CognitiveDefaults.empty(key.agentId()));

        DescriptorView effectiveView = baseView;
        if (baseView != null && state != null && !state.facets().isEmpty()) {
            effectiveView = LivedComposition.compose(baseView, state, true);
        }
        CognitiveDefaults effectiveDefaults = effectiveView != null
                ? merge(explicit, CognitiveDerivationEngine.derive(effectiveView, observer))
                : restingDefaults;

        return new Slot(baseView, state,
                LearningProfile.from(restingDefaults),
                effectiveDefaults, bv, sv, cv, Instant.now());
    }

    public static CognitiveDefaults merge(CognitiveDefaults explicit, CognitiveDefaults derived) {
        if (explicit == null) return derived;
        if (derived == null) return explicit;
        var mergedProfile = mergePersonalityProfile(
                explicit.personalityProfile(), derived.personalityProfile());
        var mergedEmotion = explicit.emotionDefaults().mergeWith(derived.emotionDefaults());
        var mergedResources = explicit.cognitiveResourceDefaults()
                .mergeWith(derived.cognitiveResourceDefaults());
        var mergedGraph = explicit.graphDefaults().mergeWith(derived.graphDefaults());
        var mergedBehavioral = explicit.behavioralDefaults()
                .mergeWith(derived.behavioralDefaults());
        return new CognitiveDefaults(derived.agentId(), explicit.tenantId(),
                mergedProfile, derived.descriptor(),
                mergedEmotion, mergedResources, mergedGraph, mergedBehavioral,
                explicit.briefing());
    }

    private static PersonalityProfile mergePersonalityProfile(
            PersonalityProfile explicit, PersonalityProfile derived) {
        if (explicit == null) return derived;
        if (derived == null) return explicit;
        return new PersonalityProfile(
                explicit.personality() != null ? explicit.personality() : derived.personality(),
                explicit.personalityDominance() != null
                        ? explicit.personalityDominance() : derived.personalityDominance(),
                explicit.expressionStyles() != null
                        ? explicit.expressionStyles() : derived.expressionStyles(),
                explicit.attachment() != null ? explicit.attachment() : derived.attachment(),
                !explicit.facets().isEmpty() ? explicit.facets() : derived.facets()
        );
    }

    private long currentBaseVersion(TenantAgentKey key) {
        var v = baseVersions.get(key);
        return v != null ? v.get() : 0;
    }

    private long currentCompositionVersion(TenantAgentKey key) {
        return stateSource.compositionVersion(key.tenantId(), key.agentId());
    }

    private void incrementBaseVersion(TenantAgentKey key) {
        baseVersions.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    static final class Slot {
        final DescriptorView baseView;
        final PersonalityStateSnapshot stateSnapshot;
        final LearningProfile resting;
        final CognitiveDefaults effective;
        final long baseVersion;
        final long stateVersion;
        final long compositionVersion;
        volatile Instant lastAccess;

        Slot(DescriptorView baseView, PersonalityStateSnapshot stateSnapshot,
             LearningProfile resting, CognitiveDefaults effective,
             long baseVersion, long stateVersion, long compositionVersion, Instant lastAccess) {
            this.baseView = baseView;
            this.stateSnapshot = stateSnapshot;
            this.resting = resting;
            this.effective = effective;
            this.baseVersion = baseVersion;
            this.stateVersion = stateVersion;
            this.compositionVersion = compositionVersion;
            this.lastAccess = lastAccess;
        }

        boolean isStale(long currentBaseVersion, long currentCompositionVersion) {
            lastAccess = Instant.now();
            return baseVersion < currentBaseVersion || compositionVersion < currentCompositionVersion;
        }
    }
}
