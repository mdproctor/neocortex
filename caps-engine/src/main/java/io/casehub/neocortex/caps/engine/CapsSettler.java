package io.casehub.neocortex.caps.engine;

import io.casehub.neocortex.caps.AgentCapsState;
import io.casehub.neocortex.caps.CapsAttractor;
import io.casehub.neocortex.caps.CapsConnection;
import io.casehub.neocortex.caps.CapsNode;
import io.casehub.neocortex.caps.CapsTopology;
import io.casehub.neocortex.caps.ConnectionWeight;
import io.casehub.neocortex.caps.ConvergenceType;
import io.casehub.neocortex.caps.DistortionDefinition;
import io.casehub.neocortex.caps.DistortionEffect;
import io.casehub.neocortex.caps.NodeRange;
import io.casehub.neocortex.caps.NodeState;
import io.casehub.neocortex.caps.NodeType;
import io.casehub.neocortex.caps.SettlingResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CapsSettler {

    private final CapsTopology       topology;
    private final CapsSettlingConfig config;

    public CapsSettler(CapsTopology topology) {
        this(topology, CapsSettlingConfig.defaults());
    }

    public CapsSettler(CapsTopology topology, CapsSettlingConfig config) {
        this.topology = topology;
        this.config   = config;
    }

    public SettlingResult settle(AgentCapsState state,
                                 Map<String, Double> inputActivations) {
        var                 params      = topology.weightUpdate();
        Map<String, Double> activations = new HashMap<>();

        for (var entry : topology.nodes().entrySet()) {
            CapsNode node = entry.getValue();
            if (node.type() == NodeType.INPUT) {
                activations.put(node.id(),
                                inputActivations.getOrDefault(node.id(), 0.0));
            } else {
                NodeState ns = state.nodeStates()
                                    .getOrDefault(node.id(), NodeState.DEFAULT);
                activations.put(node.id(), ns.restingActivation());
            }
        }

        Map<String, List<CapsConnection>> incomingByNode = new HashMap<>();
        for (CapsConnection conn : topology.connections()) {
            incomingByNode.computeIfAbsent(conn.to(), k -> new ArrayList<>())
                          .add(conn);
        }

        Map<String, Double>       prevPrev   = null;
        List<Map<String, Double>> lastTen    = new ArrayList<>();
        int                       iterations = 0;

        for (int iter = 0; iter < params.maxIterations(); iter++) {
            iterations = iter + 1;
            Map<String, Double> newActivations = new HashMap<>(activations);

            for (var entry : topology.nodes().entrySet()) {
                CapsNode node = entry.getValue();
                if (node.type() == NodeType.INPUT) {continue;}

                List<CapsConnection> incoming = incomingByNode
                                                        .getOrDefault(node.id(), List.of());
                double netInput = 0.0;
                for (CapsConnection conn : incoming) {
                    ConnectionWeight cw = state.weights()
                                               .getOrDefault(conn.id(),
                                                             new ConnectionWeight(conn.defaultWeight(), 0.0, 1.0, 1.0));
                    double sourceActivation = activations
                                                      .getOrDefault(conn.from(), 0.0);
                    netInput += sourceActivation * cw.effectiveWeight();
                }

                netInput = applyDistortions(node, activations.get(node.id()),
                                            netInput, state);

                NodeState ns = state.nodeStates()
                                    .getOrDefault(node.id(), NodeState.DEFAULT);

                // Activation functions — Rumelhart, Hinton & Williams (1986) PDP; tanh=bipolar, logistic=unipolar
                double rawAct;
                if (node.range() == NodeRange.BIPOLAR) {
                    rawAct = Math.tanh(netInput - ns.activationThreshold());
                } else {
                    rawAct = logistic(netInput - ns.activationThreshold());
                }

                // Momentum damping — Hopfield (1984): blend with previous to suppress multi-cycle oscillation
                double oldAct = activations.getOrDefault(node.id(), 0.0);
                double newAct = config.dampingFactor() * oldAct + (1.0 - config.dampingFactor()) * rawAct;

                newActivations.put(node.id(), newAct);
            }

            double maxDelta = 0.0;
            for (var entry : topology.nodes().entrySet()) {
                if (entry.getValue().type() == NodeType.INPUT) {continue;}
                double delta = Math.abs(
                        newActivations.get(entry.getKey()) -
                        activations.get(entry.getKey()));
                maxDelta = Math.max(maxDelta, delta);
            }

            // Two-cycle oscillation detection with mean extraction — Hopfield (1982) attractor dynamics
            if (prevPrev != null) {
                double osc = 0.0;
                for (var entry : topology.nodes().entrySet()) {
                    if (entry.getValue().type() == NodeType.INPUT) {continue;}
                    osc = Math.max(osc, Math.abs(
                            newActivations.get(entry.getKey()) -
                            prevPrev.get(entry.getKey())));
                }
                if (osc < params.epsilon()) {
                    Map<String, Double> averaged = new HashMap<>(newActivations);
                    for (var entry : topology.nodes().entrySet()) {
                        if (entry.getValue().type() == NodeType.INPUT) {continue;}
                        String key = entry.getKey();
                        averaged.put(key, (newActivations.get(key) + activations.get(key)) / 2.0);
                    }
                    return buildResult(averaged, null,
                                       iterations, ConvergenceType.OSCILLATION, state);
                }
            }

            prevPrev    = activations;
            activations = newActivations;

            if (lastTen.size() >= 10) {lastTen.removeFirst();}
            lastTen.add(Map.copyOf(activations));

            if (maxDelta < params.epsilon()) {
                return buildResult(activations, null,
                                   iterations, ConvergenceType.CONVERGED, state);
            }

            // Wider oscillation detection — average last N states when variance is small
            if (lastTen.size() >= 5) {
                double windowMaxVar = windowMaxVariance(lastTen, topology);
                if (windowMaxVar < params.epsilon() * 3.0) {
                    Map<String, Double> averaged = averageStates(lastTen, topology);
                    return buildResult(averaged, null,
                                       iterations, ConvergenceType.OSCILLATION, state);
                }
            }
        }

        Map<String, Double> bestState = averageStates(lastTen, topology);
        return buildResult(bestState, null,
                           iterations, ConvergenceType.MAX_ITERATIONS, state);
    }

    private double applyDistortions(CapsNode node, double currentActivation,
                                    double netInput, AgentCapsState state) {
        double compoundMultiplier = 1.0;
        double additiveTotal      = 0.0;

        for (DistortionDefinition dist : topology.distortions()) {
            if (!dist.targetCategories().contains(node.category())) {continue;}

            double effectiveThreshold = dist.baseThreshold() * (
                    1.0 - dist.negativeWeight() * Math.max(0, -currentActivation)
                    - dist.positiveWeight() * Math.max(0, currentActivation));
            effectiveThreshold = Math.max(config.minEffectiveDistortionThreshold(), effectiveThreshold);

            if (Math.abs(currentActivation) <= effectiveThreshold) {continue;}

            double t = (Math.abs(currentActivation) - effectiveThreshold)
                       / (1.0 - effectiveThreshold);
            t = Math.max(0, Math.min(1, t));
            double mult = dist.multiplierMin() + t * (dist.multiplierMax() - dist.multiplierMin());

            if (dist.effect() == DistortionEffect.MULTIPLICATIVE) {
                compoundMultiplier *= mult;
            } else {
                additiveTotal += mult;
            }
        }

        var cap = topology.weightUpdate().compoundDistortionCap();
        compoundMultiplier = Math.max(cap[0], Math.min(cap[1], compoundMultiplier));

        double result = netInput * compoundMultiplier + additiveTotal;
        var    wCap   = topology.weightUpdate().effectiveWeightCap();
        return Math.max(wCap[0], Math.min(wCap[1], result));
    }

    private double logistic(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private SettlingResult buildResult(Map<String, Double> primaryState,
                                       Map<String, Double> secondaryState,
                                       int iterations,
                                       ConvergenceType convergence,
                                       AgentCapsState agentState) {
        double  saturationRatio = computeSaturation(primaryState);
        boolean highSat         = saturationRatio > topology.weightUpdate().saturationWarningThreshold();

        List<CapsAttractor> attractors = new ArrayList<>();
        extractAttractors(primaryState, attractors, highSat,
                          agentState.generation(), 1.0, agentState);

        return new SettlingResult(primaryState, iterations,
                                  convergence, saturationRatio, List.copyOf(attractors));
    }

    // Attractor strength = settled activation + disposition baseline — Read et al. (2010)
    private void extractAttractors(Map<String, Double> activations,
                                   List<CapsAttractor> attractors,
                                   boolean highSat, long generation,
                                   double strengthMultiplier,
                                   AgentCapsState agentState) {
        for (var entry : topology.nodes().entrySet()) {
            CapsNode node = entry.getValue();
            if (node.type() != NodeType.OUTPUT) {continue;}
            double settled = activations.getOrDefault(node.id(), 0.0);
            NodeState ns = agentState.nodeStates()
                                     .getOrDefault(node.id(), NodeState.DEFAULT);
            double strength = Math.min(1.0, settled + ns.restingActivation())
                              * strengthMultiplier;
            if (strength > config.attractorStrengthMinFilter()) {
                attractors.add(new CapsAttractor(
                        node.category(), node.id(), strength, highSat, generation));
            }
        }
    }

    private double computeSaturation(Map<String, Double> activations) {
        long mediating = topology.nodes().values().stream()
                                 .filter(n -> n.type() == NodeType.MEDIATING).count();
        if (mediating == 0) {return 0;}
        long saturated = topology.nodes().values().stream()
                                 .filter(n -> n.type() == NodeType.MEDIATING)
                                 .filter(n -> Math.abs(activations.getOrDefault(n.id(), 0.0)) > config.saturationActivationThreshold())
                                 .count();
        return (double) saturated / mediating;
    }

    private static double windowMaxVariance(List<Map<String, Double>> states, CapsTopology topo) {
        double maxVar = 0.0;
        for (var entry : topo.nodes().entrySet()) {
            if (entry.getValue().type() == NodeType.INPUT) continue;
            String nodeId = entry.getKey();
            double sum = 0, sumSq = 0;
            int n = states.size();
            for (var s : states) {
                double v = s.getOrDefault(nodeId, 0.0);
                sum += v;
                sumSq += v * v;
            }
            double mean = sum / n;
            double var = sumSq / n - mean * mean;
            maxVar = Math.max(maxVar, var);
        }
        return maxVar;
    }

    private static Map<String, Double> averageStates(List<Map<String, Double>> states, CapsTopology topo) {
        Map<String, Double> averaged = new HashMap<>();
        int n = states.size();
        for (var entry : topo.nodes().entrySet()) {
            String nodeId = entry.getKey();
            if (entry.getValue().type() == NodeType.INPUT) {
                averaged.put(nodeId, states.getLast().getOrDefault(nodeId, 0.0));
            } else {
                double sum = 0;
                for (var s : states) sum += s.getOrDefault(nodeId, 0.0);
                averaged.put(nodeId, sum / n);
            }
        }
        return averaged;
    }

    private Map<String, Double> findMostSettled(List<Map<String, Double>> states) {
        if (states.size() < 2) {return states.getLast();}
        Map<String, Double> best         = states.getLast();
        double              bestMaxDelta = Double.MAX_VALUE;
        for (int i = 1; i < states.size(); i++) {
            double maxDelta = 0;
            for (var key : states.get(i).keySet()) {
                maxDelta = Math.max(maxDelta,
                                    Math.abs(states.get(i).get(key) - states.get(i - 1).get(key)));
            }
            if (maxDelta < bestMaxDelta) {
                bestMaxDelta = maxDelta;
                best         = states.get(i);
            }
        }
        return best;
    }
}
