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
package io.casehub.neocortex.cognitive.eidos;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.DispositionAxis;
import io.casehub.neocortex.cognitive.index.PersonalityFacet;
import io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry;
import io.casehub.neocortex.cognitive.index.CognitiveDerivationEngine;
import io.casehub.neocortex.cognitive.index.DescriptorView;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.neocortex.cognitive.index.WeightedTerm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.logging.Logger;

@ApplicationScoped
public class EidosCognitiveProfileBridge {

    private static final Logger LOG = Logger.getLogger(EidosCognitiveProfileBridge.class.getName());

    private final CognitiveDefaultsRegistry registry;
    private final Instance<AgentRegistry> agentRegistryInstance;
    private final AgentRegistry directAgentRegistry;

    @Inject
    public EidosCognitiveProfileBridge(CognitiveDefaultsRegistry registry,
                                       Instance<AgentRegistry> agentRegistryInstance) {
        this.registry = registry;
        this.agentRegistryInstance = agentRegistryInstance;
        this.directAgentRegistry = null;
    }

    EidosCognitiveProfileBridge(CognitiveDefaultsRegistry registry) {
        this.registry = registry;
        this.agentRegistryInstance = null;
        this.directAgentRegistry = null;
    }

    EidosCognitiveProfileBridge(CognitiveDefaultsRegistry registry, AgentRegistry agentRegistry) {
        this.registry = registry;
        this.agentRegistryInstance = null;
        this.directAgentRegistry = agentRegistry;
    }

    public void bridgeAll(String tenancyId) {
        AgentRegistry agentRegistry = directAgentRegistry;
        if (agentRegistry == null && agentRegistryInstance != null && !agentRegistryInstance.isUnsatisfied()) {
            agentRegistry = agentRegistryInstance.get();
        }
        if (agentRegistry == null) {
            LOG.info("No AgentRegistry available — skipping eidos profile bridge");
            return;
        }
        var matches = agentRegistry.find(AgentQuery.all(tenancyId));
        int count = 0;
        for (var match : matches) {
            var view = toDescriptorView(match.descriptor());
            registerFromDescriptor(view);
            count++;
        }
        LOG.info("Registered " + count + " cognitive profiles from eidos descriptors");
    }

    public void registerFromDescriptor(DescriptorView view) {
        var existing = registry.forAgent(view.agentId());
        if (existing.isPresent()) {
            var merged = CognitiveDerivationEngine.deriveAndMerge(existing.get().withDescriptor(view));
            registry.register(merged);
        } else {
            var defaults = CognitiveDerivationEngine.derive(view);
            registry.register(defaults);
        }
    }

    static DescriptorView toDescriptorView(AgentDescriptor descriptor) {
        var disposition = descriptor.disposition();
        var axes = new DispositionAxes(
                disposition.primaryTerm(DispositionAxis.SOCIAL_ORIENTATION),
                disposition.primaryTerm(DispositionAxis.RULE_FOLLOWING),
                disposition.primaryTerm(DispositionAxis.RISK_APPETITE),
                disposition.primaryTerm(DispositionAxis.AUTONOMY),
                disposition.primaryTerm(DispositionAxis.CONFLICT_MODE)
        );
        var profile = disposition.dispositionProfile().stream()
                                 .map(dv -> new WeightedTerm(dv.term(), dv.weight()))
                                 .toList();
        var goals = descriptor.goals().stream()
                              .map(AgentGoal::name)
                              .toList();
        var facets = disposition.ampdFacets().stream()
                                .map(af -> new PersonalityFacet(titleCase(af.facet()), af.score() / 100.0, "eidos"))
                                .toList();
        return new DescriptorView(descriptor.agentId(), axes, profile, goals, null, facets);
    }

    private static String titleCase(String s) {
        if (s == null || s.isEmpty()) {return s;}
        var     sb        = new StringBuilder();
        boolean nextUpper = true;
        for (char c : s.toCharArray()) {
            if (Character.isWhitespace(c)) {
                nextUpper = true;
                sb.append(c);
            } else if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                sb.append(Character.toLowerCase(c));
                nextUpper = false;
            }
        }
        return sb.toString();
    }

}
