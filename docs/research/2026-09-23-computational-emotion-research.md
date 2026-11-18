# Computational Emotion Models: Research, Datasets, and Integration Path

## 1. Foundational Emotion Theories

### 1.1 The OCC Model (Ortony, Clore, Collins 1988)

The most widely adopted computational emotion framework. Defines 22 emotion types as
products of cognitive appraisal — evaluating events, agents, and objects against goals,
standards, and attitudes.

**Core principle:** Emotions are not inherent in events but arise from how an agent
appraises those events relative to its own goals.

**Three branches of valenced reactions:**

| Branch | Appraisal target | Criterion | Valence |
|--------|-----------------|-----------|---------|
| Events | Consequences for goals | Desirability | Pleased / Displeased |
| Agents | Actions judged by standards | Praiseworthiness | Approving / Disapproving |
| Objects | Aspects of objects/entities | Appealingness | Liking / Disliking |

**The 22 emotion types:**

**Event-based (consequences of events):**

| Type | Appraisal pattern | Example |
|------|------------------|---------|
| Joy | Desirable event confirmed | Goal achieved |
| Distress | Undesirable event confirmed | Goal failed |
| Hope | Desirable outcome, uncertain prospect | Approaching deadline, could succeed |
| Fear | Undesirable outcome, uncertain prospect | Approaching deadline, might miss |
| Satisfaction | Hope confirmed — hoped-for outcome realized | Goal completed as planned |
| Disappointment | Hope disconfirmed — hoped-for outcome failed | Goal missed despite expectations |
| Relief | Fear disconfirmed — feared outcome avoided | Last-minute save |
| Fears-confirmed | Fear confirmed — feared outcome realized | "I was right to worry" |
| Happy-for | Desirable event for liked other | Empathic joy |
| Resentment | Desirable event for disliked other | Envious displeasure |
| Gloating | Undesirable event for disliked other | Schadenfreude |
| Pity (Sorry-for) | Undesirable event for liked other | Empathic concern |

**Agent-based (actions of agents):**

| Type | Appraisal pattern | Example |
|------|------------------|---------|
| Pride | Own action praiseworthy | "I'm glad I kept reminding you" |
| Shame | Own action blameworthy | "I should have been more insistent" |
| Admiration | Other's action praiseworthy | Respect for someone's effort |
| Reproach | Other's action blameworthy | "You should have acted on this" |

**Object-based (aspects of objects):**

| Type | Appraisal pattern | Example |
|------|------------------|---------|
| Love | Object/entity appealing | Attachment to a person/thing |
| Hate | Object/entity unappealing | Aversion to a person/thing |

**Compound (event + agent combined):**

| Type | Components | Example |
|------|-----------|---------|
| Gratitude | Joy + Admiration | "Thank you for ordering the gift" |
| Anger | Distress + Reproach | Frustration at external blocker |
| Gratification | Joy + Pride | Satisfaction at own successful action |
| Remorse | Distress + Shame | Regret at own failure to act |

**Intensity variables** (determine degree of each emotion):

| Variable | What it measures | Affects |
|----------|-----------------|---------|
| Desirability | How much the goal matters | All event-based emotions |
| Likelihood | Probability of the outcome | Prospect-based (Hope, Fear) |
| Proximity | Temporal/physical closeness | All emotions (amplifier) |
| Unexpectedness | How surprising the event is | All emotions (amplifier) |
| Sense of reality | How confirmed/real | All emotions (threshold) |
| Effort invested | How much work was put in | Confirmation emotions |
| Praiseworthiness | How praiseworthy/blameworthy | Agent-based emotions |
| Appealingness | How attractive/repulsive | Object-based emotions |

**Intensity formula:**
```
I_emotion = Σ(w_i × A_i) - threshold
```
Where A_i are appraisal variables, w_i are weights (personality-dependent),
and threshold is the minimum intensity for the emotion to be "felt."

**Key references:**
- Ortony, A., Clore, G. L., & Collins, A. (1988). *The Cognitive Structure of Emotions*. Cambridge University Press.
- Steunebrink, B. R., Dastani, M., & Meyer, J.-J. Ch. (2009). "The OCC Model Revisited." *KI 2009*.
- Ortony, A., Clore, G. L., & Collins, A. (2022). *The Cognitive Structure of Emotions* (2nd ed.). Cambridge University Press.

### 1.2 Scherer's Component Process Model (CPM, 2001)

A process-oriented model that defines emotion as an emergent phenomenon from sequential
appraisal checks. Unlike OCC's categorical approach, CPM allows for an infinite number
of emotion types based on the pattern of appraisal results.

**Five emotion components:**
1. Cognitive appraisal
2. Physiological response
3. Motor expression
4. Motivational tendency (action readiness)
5. Subjective feeling

**Stimulus Evaluation Checks (SECs) — the appraisal process:**

| Stage | Check | Subchecks | What it evaluates |
|-------|-------|-----------|-------------------|
| 1 | **Relevance** | Suddenness, Familiarity, Predictability | Is this event novel/significant? |
| 1 | **Intrinsic pleasantness** | (unitary) | Is this inherently pleasant/unpleasant? |
| 1 | **Goal/Need relevance** | Concern relevance | Does this matter to my goals? |
| 2 | **Causality** | Agent, Motive | Who/what caused this? Why? |
| 2 | **Outcome probability** | (unitary) | How likely is this outcome? |
| 2 | **Discrepancy from expectation** | (unitary) | How surprising is this? |
| 2 | **Goal conduciveness** | (unitary) | Does this help or hinder my goal? |
| 2 | **Urgency** | (unitary) | How quickly must I respond? |
| 3 | **Control** | (unitary) | Can I control this situation? |
| 3 | **Power** | (unitary) | Do I have the resources to cope? |
| 3 | **Adjustment** | (unitary) | Can I adapt to this outcome? |
| 4 | **External standards** | Social norms | Does this violate social expectations? |
| 4 | **Internal standards** | Self-concept | Does this conflict with my values? |

**Processing order:** Novelty → Intrinsic pleasantness → Goal relevance →
Goal conduciveness → Coping potential → Norm compatibility

**Key insight for our system:** The Coping Potential check (Stage 3) distinguishes
Fear (low control — agent can only remind) from Anger/Frustration (agent tried
and was blocked). This maps directly to the agency distinction we identified.

**Key references:**
- Scherer, K. R. (2001). "Appraisal considered as a process of multilevel sequential checking." In *Appraisal Processes in Emotion*, Oxford University Press.
- Scherer, K. R. (2009). "The dynamic architecture of emotion: Evidence for the component process model." *Cognition and Emotion*, 23(7), 1307–1351.
- Sander, D., Grandjean, D., & Scherer, K. R. (2005). "A systems approach to appraisal mechanisms in emotion." *Neural Networks*, 18, 317–352.

### 1.3 PAD Emotional State Model (Mehrabian & Russell 1974)

A dimensional model using three nearly orthogonal axes to represent the continuous
emotional space. Originally developed for environmental psychology, now the standard
continuous representation in affective computing.

**Three dimensions:**

| Dimension | Range | Low end | High end |
|-----------|-------|---------|----------|
| **Pleasure (P)** | [-1, +1] | Unpleasant, unhappy | Pleasant, happy |
| **Arousal (A)** | [-1, +1] | Calm, sleepy | Excited, energized |
| **Dominance (D)** | [-1, +1] | Submissive, controlled | Dominant, in-control |

**Eight mood octants** (from PAD sign combinations):

| Octant | P | A | D | Label | Characterization |
|--------|---|---|---|-------|-----------------|
| +P+A+D | + | + | + | **Exuberant** | Joyful, triumphant, proud |
| +P+A-D | + | + | - | **Dependent** | Grateful, hopeful, admiring |
| +P-A+D | + | - | + | **Relaxed** | Satisfied, content, calm |
| +P-A-D | + | - | - | **Docile** | Relieved, tranquil |
| -P+A+D | - | + | + | **Hostile** | Angry, hateful, aggressive |
| -P+A-D | - | + | - | **Anxious** | Fearful, disappointed, remorseful |
| -P-A+D | - | - | + | **Disdainful** | Contemptuous, reproachful |
| -P-A-D | - | - | - | **Bored** | Distressed, despairing, pitying |

**Critical finding (Limbrecht et al.):** Pleasure maps reliably across individuals,
but Arousal and Dominance are personality-dependent — they require individual
calibration. This validates the use of personality-modulated PAD baselines.

**Key references:**
- Mehrabian, A. & Russell, J. A. (1974). *An Approach to Environmental Psychology*. MIT Press.
- Mehrabian, A. (1996). "Pleasure-arousal-dominance: A general framework for describing and measuring individual differences in Temperament." *Current Psychology*, 14, 261–292.
- Limbrecht-Ecklundt, K. et al. (2013). "Pleasure, Arousal, Dominance: Mehrabian and Russell revisited."

### 1.4 Plutchik's Wheel of Emotions (1980)

An evolutionary model with 8 primary emotions arranged in 4 opposing pairs.
Less computationally tractable than OCC but useful for understanding emotion
relationships and blends.

**8 primary emotions:** Joy, Trust, Fear, Surprise, Sadness, Disgust, Anger, Anticipation

**Key contribution:** The concept of emotion *blends* (e.g., Joy + Trust = Love,
Anger + Disgust = Contempt). Less relevant for our system than OCC's goal-oriented
appraisal, but the blending concept could inform compound emotion detection.

---

## 2. Computational Architectures

### 2.1 ALMA — A Layered Model of Affect (Gebhard 2005)

The most directly relevant architecture for our system. Integrates OCC emotions,
PAD mood, and personality in a three-layer model.

**Three layers:**

```
┌─────────────────────────────────────────────────┐
│ Layer 3: PERSONALITY                            │
│   OCEAN traits → PAD baseline                   │
│   Stable, long-term, minimal drift              │
│   Determines appraisal thresholds + mood anchor │
├─────────────────────────────────────────────────┤
│ Layer 2: MOOD                                   │
│   PAD state with temporal dynamics              │
│   Medium-term, influenced by emotion layer      │
│   Decays toward personality baseline             │
│   Biases subsequent appraisals (mood congruence)│
├─────────────────────────────────────────────────┤
│ Layer 1: EMOTION                                │
│   OCC appraisal → discrete emotion + intensity  │
│   Short-lived, event-driven, decays rapidly     │
│   Pushes mood state in corresponding direction  │
└─────────────────────────────────────────────────┘
```

**OCC → PAD mapping table (complete, from Gebhard 2005):**

| # | OCC Emotion | P | A | D | Mood Octant |
|---|-------------|------|------|------|-------------|
| 1 | Admiration | +0.5 | +0.3 | -0.2 | Dependent |
| 2 | Anger | -0.51 | +0.59 | +0.25 | Hostile |
| 3 | Disappointment | -0.3 | +0.1 | -0.4 | Anxious |
| 4 | Disliking | -0.4 | +0.2 | +0.1 | Hostile |
| 5 | Distress | -0.4 | -0.2 | -0.5 | Bored |
| 6 | Fear | -0.64 | +0.60 | -0.43 | Anxious |
| 7 | Fears-confirmed | -0.5 | -0.3 | -0.7 | Bored |
| 8 | Gloating | +0.3 | -0.3 | -0.1 | Docile |
| 9 | Gratification | +0.6 | +0.5 | +0.4 | Exuberant |
| 10 | Gratitude | +0.4 | +0.2 | -0.3 | Dependent |
| 11 | Happy-for | +0.4 | +0.2 | +0.2 | Exuberant |
| 12 | Hate | -0.6 | +0.6 | +0.3 | Hostile |
| 13 | Hope | +0.2 | +0.2 | -0.1 | Dependent |
| 14 | Joy | +0.4 | +0.2 | +0.1 | Exuberant |
| 15 | Liking | +0.40 | +0.16 | -0.24 | Dependent |
| 16 | Pity | -0.4 | -0.2 | -0.5 | Bored |
| 17 | Pride | +0.4 | +0.3 | +0.3 | Exuberant |
| 18 | Relief | +0.2 | -0.3 | -0.4 | Docile |
| 19 | Remorse | -0.3 | +0.1 | -0.6 | Anxious |
| 20 | Reproach | -0.3 | -0.1 | +0.4 | Disdainful |
| 21 | Resentment | -0.2 | -0.3 | -0.2 | Bored |
| 22 | Satisfaction | +0.3 | -0.2 | +0.4 | Relaxed |
| 23 | Shame | -0.3 | +0.1 | -0.6 | Anxious |

Note: Remorse and Shame share identical PAD values (-0.3, +0.1, -0.6).
Distress and Pity also share values (-0.4, -0.2, -0.5). This reflects
their similar felt experience despite different appraisal origins.

**OCEAN → PAD baseline mapping (Gebhard 2005, from Mehrabian 1996):**

```
P = 0.21 × Extraversion + 0.59 × Agreeableness + 0.19 × Neuroticism
A = 0.15 × Openness + 0.30 × Agreeableness − 0.57 × Neuroticism
D = 0.25 × Openness + 0.17 × Conscientiousness + 0.60 × Extraversion − 0.32 × Agreeableness
```

These equations produce the personality-derived PAD baseline — the "default mood"
to which an agent's mood returns over time. Mehrabian (1996) showed the PAD scales
explain ~75% of the reliable variance in three OCEAN factors (Extraversion,
Emotional Stability, Agreeableness).

**Mood decay dynamics:**
- Mood decays toward the personality baseline
- Return time proportional to distance from baseline
- Maximum distance in a mood octant: √3 (diagonal of unit cube)
- Decay is smooth and continuous, not stepped

**Key references:**
- Gebhard, P. (2005). "ALMA — A Layered Model of Affect." *AAMAS 2005*.
- Gebhard, P. & Kipp, K. H. (2006). "Are computer-generated emotions and moods plausible to humans?" *IVA 2006*.

### 2.2 WASABI (Becker-Asano 2008)

Combines embodied simulation with cognitive appraisal. Two parallel processes:

1. **Emotional process** — continuous PAD space ("Core Affect"), fast, reactive
2. **Cognitive process** — OCC appraisal, slow, deliberative, generates "secondary emotions"

**Key contributions:**
- Primary emotions arise from embodied signals (fast, low-level)
- Secondary emotions arise from OCC cognitive appraisal (slow, high-level)
- Both modulate the same PAD mood space
- Emotion intensity decays exponentially toward neutral

**Key reference:**
- Becker-Asano, C. (2008). *WASABI: Affect Simulation for Agents with Believable Interactivity*. PhD Thesis, University of Bielefeld.

### 2.3 FAtiMA Toolkit (Dias et al. 2014)

An accessible toolkit for socio-emotional agents, combining OCC appraisal with
BDI reasoning and a meta-cognitive layer.

**Key contributions:**
- Library-based design for easy integration
- Meta-beliefs about the agent's own emotional state
- Declarative rules for how emotions affect behaviour
- Explicit dialogue structure for complex scenarios
- Scherer-inspired appraisal process

**Key reference:**
- Dias, J. et al. (2022). "FAtiMA Toolkit: Toward an Accessible Tool for the Development of Socio-emotional Agents." *ACM TIIS*, 12(2).

### 2.4 EMA (Marsella & Gratch 2009)

Emotion and Adaptation framework based on Lazarus's cognitive-motivational-relational
theory. More focused on coping strategies than OCC.

**Key contribution:** Models not just emotion generation but emotion *regulation* —
how agents cope with and manage their emotional states. This is relevant for our
system's decay and adjustment mechanisms.

**Implementation status (Nov 2026):** EMA coping is now designed and partially
implemented (#595). The design adopts the full 16-strategy taxonomy (not a simplified
3-mode version), organized by the mental representation each strategy targets
(attention, belief, desire, intention). An intra-tick micro-loop provides the
appraisal-coping-reappraisal cycle: the LLM reinterprets the situation narrative,
LLM-based appraisal re-evaluates SEC dimensions, and the loop converges or iterates.
(Keyword SEC checks were removed in #1113 — `CopingMicroLoop.reEvaluate()` now
delegates to `AppraisalStrategy`.) Coping sits between Scherer appraisal and Gross
regulation — coping selects the strategy, regulation executes it. See `specs/issue-595-coping-strategies/2026-11-15-coping-strategies-design.md`.

**Key reference:**
- Marsella, S. & Gratch, J. (2009). "EMA: A process model of appraisal dynamics."
*Cognitive Systems Research*, 10(1), 70–90.

---

## 3. Temporal Dynamics

### 3.1 Emotion Decay Functions

Four decay types identified in computational emotion literature:

| Type | Behaviour | Use case |
|------|-----------|----------|
| **Linear** | Constant decay rate | Simplest, most common in implementations |
| **Exponential** | Rapid initial decay, long tail | Most psychologically realistic |
| **Logarithmic** | Rapid early decay, then plateau | Less common |
| **Tanh** | Strong hold, rapid drop, then stable | For emotions with "inertia" |

**Empirical finding:** Most emotional half-lives are under 1 hour, with many
in the 5-minute range. This confirms they are emotions (short-lived) rather
than moods (extended). Source: computational phenotyping studies using
experience sampling methods.

### 3.2 Mood Dynamics (ALMA model)

```
mood(t+1) = mood(t) + α × (emotion_pad - mood(t)) - β × (mood(t) - baseline)
```

Where:
- α = emotion influence rate (~10% of distance per cycle)
- β = decay rate toward baseline (~1% per cycle)
- emotion_pad = PAD coordinates of current active emotion
- baseline = personality-derived PAD default

**Mood congruence effect:** Current mood biases subsequent appraisals.
An agent in an anxious mood will appraise ambiguous events more negatively.
This creates positive feedback loops (worry → anxious mood → more worry)
that are bounded by the baseline decay.

### 3.3 Emotional Half-Life

Research using logistic regression on experience-sampling data found:
- Most discrete emotions have half-lives under 1 hour
- The half-life varies by emotion type and individual
- This supports the emotion→mood accumulation model: individual emotions
  are brief, but their cumulative effect shifts the longer-lasting mood

**Implication for our system:** Goal-related emotions (Fear about a deadline)
should decay within a tick cycle unless re-triggered. But their accumulated
effect on mood persists. Surfacing a goal 7 times over 7 days doesn't
maintain a constant Fear — it re-triggers Fear 7 times, each instance
pushing the mood further into the Anxious octant, where mood decay is
slower than emotion decay.

---

## 4. Available Datasets

### 4.1 Situation → Emotion + Appraisal (Text-based)

| Dataset | Size | Emotions | Features | Access |
|---------|------|----------|----------|--------|
| **ISEAR** | 7,666 situations, 3,000 respondents, 37 countries | Joy, Fear, Anger, Sadness, Disgust, Shame, Guilt | Situation description + appraisal data + intensity + duration | [UNIGE CISA](https://www.unige.ch/cisa/research/materials-and-online-research/research-material) |
| **enISEAR** | 1,001 English event descriptions | Same 7 + appraisal annotations | OCC appraisal dimensions layered on ISEAR | [ACL Anthology](https://aclanthology.org/2020.coling-main.11.pdf) |
| **GoEmotions** | 58,000 Reddit comments | 27 fine-grained (12 positive, 11 negative, 4 ambiguous, 1 neutral) | Multi-label annotations | [Google Research](https://research.google/blog/goemotions-a-dataset-for-fine-grained-emotion-classification/) |
| **EmoBank** | 10,000 sentences | VAD continuous ratings | Reader AND writer perspectives, Pearson r=0.64–0.72 | [Kaggle](https://www.kaggle.com/datasets/jackksoncsie/emobank) |
| **DailyDialog** | 13,118 dialogues | 7 emotions | Multi-turn conversation context | Public |
| **EmotionLines** | 29,245 utterances (Friends) | 7 emotions | Dialogue context with speaker info | Public |

**ISEAR is the gold standard for our use case** because it has BOTH situation
descriptions AND appraisal data — people reported HOW they appraised the
situation (novelty, pleasantness, goal conduciveness, coping potential),
not just WHAT emotion they felt. This is the Scherer SEC → emotion mapping
as training data, cross-culturally validated across 37 countries.

### 4.2 Stimulus → PAD Ratings (Images/Audio/Words)

| Dataset | Size | Dimensions | Modality | Access |
|---------|------|------------|----------|--------|
| **IAPS** | 1,182 images | Valence, Arousal, Dominance (SAM 9-point) | Images | [CSEA, University of Florida](https://en.wikipedia.org/wiki/International_Affective_Picture_System) |
| **OASIS** | 900 images | Valence, Arousal | Images (open access) | Harvard, public domain |
| **NAPS** | 1,356 images | Valence, Arousal, Approach-Avoidance | Images | Nencki Institute |
| **ANEW** | 1,034 words | Valence, Arousal, Dominance | English words | [Springer](https://link.springer.com/content/pdf/10.3758/bf03193164.pdf) |
| **IADS** | 167 sounds | Valence, Arousal, Dominance | Audio | CSEA |
| **NRC VAD Lexicon** | 20,000+ words | Valence, Arousal, Dominance | English words (BWS-rated) | [NRC](https://saifmohammad.com/WebPages/nrc-vad.html) |

### 4.3 Cross-Cultural Validation

ISEAR's 37-country coverage and IAPS's extensive international norming
studies provide cross-cultural baselines. Key finding from Scherer (1997):
less complex appraisals (pleasantness, novelty) are universal; more complex
ones (immorality, responsibility) show cultural variation. For our agent
system, this means the basic appraisal→emotion mapping is culture-independent,
but moral/social appraisals may need per-agent or per-culture calibration.

### 4.4 Emerging Benchmarks (2024–2026)

| Resource | Year | Focus |
|----------|------|-------|
| **SemEval-2025 Task 11** | 2025 | Emotion intensity prediction with BERT + VAD attention |
| **SemEval-2024 Task 10** | 2024 | Emotion recognition in code-mixed conversations using NRC VAD |
| **SemEval-2024 Task 3** | 2024 | Multimodal emotion cause analysis (143 registrations) |
| **NRC VAD v2** | 2025 | Extended coverage with multi-word expressions + non-English |
| **EmoLLMs/AAID** | 2025 | Unified classification + regression via instruction-tuned LLMs |

---

## 5. Mapping to Our Existing Architecture

### 5.1 What We Already Have

| ALMA Layer | Our system | Where | Status |
|------------|-----------|-------|--------|
| **Personality** | eidos JPAF → CognitiveDerivationEngine → CognitiveDefaults | eidos + neocortex cognitive-index | ✅ Complete |
| **PAD baseline** | MoodBaseline (per-agent config, decay target) | neocortex memory-api | ✅ Complete |
| **Mood** | MoodOrchestrator (PAD state with decay) | blocks social | ✅ Complete |
| **Mood decay** | MoodDecay (exponential toward baseline) | neocortex memory-api | ✅ Complete |
| **Mood signals** | MoodSignal sealed hierarchy (InteractionAppraisal, etc.) | blocks social | ✅ Complete |
| **Goal affect** | GoalAffectPhase (PAD on goal nodes) | neocortex mindmap-intelligence | ✅ Partial |
| **Affect trajectory** | AffectTrajectoryAnalyzer (slopes, volatility, trend) | neocortex cognitive-index | ✅ Complete |
| **Curiosity** | CuriositySignalGenerator + CuriosityRefreshPhase | neocortex mindmap-intelligence | ✅ Complete |
| **Drives** | DriveOrchestrator (CURIOSITY, COMPETENCE, AFFILIATION, AUTONOMY) | blocks social | ✅ Complete |
| **Goal proposal** | GoalProposalOrchestrator (drive-based) | blocks social/goal | ✅ Complete |
| **Goal prompt** | GoalPromptSection (renders DriveGoalProposal) | blocks social/prompt | ✅ Partial |
| **Confidence** | Confidence record + ConfidenceOrigin + decay | neocortex cognitive-api | ✅ Complete |
| **Retrieval strength** | Bjorks dual-strength model | neocortex mindmap-intelligence | ✅ Complete |

### 5.2 The Gaps

| Gap | What's missing | Impact |
|-----|---------------|--------|
| **OCC appraisal layer** | No formal appraisal process — GoalAffectPhase writes raw PAD directly, bypassing emotion type classification | Lose the WHY: "arousal=0.6" doesn't distinguish Fear from Anger from Excitement |
| **Typed emotions** | No CognitiveEmotion type — emotions are represented as PAD coordinates, not as OCC types with intensity | Can't produce "I'm worried about X" — only "I feel arousal=0.6, pleasure=-0.3" |
| **Cognitive goal surfacing** | MindMap GOAL nodes not surfaced to LLM prompt | Agent is blind to its own computed goal priorities |
| **Surfacing tracking** | No record of what was surfaced when | Can't detect "told you 7 times, no action" |
| **Progress detection** | No link between surfacing history and ExperienceEvents | Can't compute surfacing-progress gap |
| **Goal lifecycle feedback** | No bridge from case completion → MindMap goal state update | Completing a case doesn't close the cognitive goal |
| **Prospect-based emotions** | Hope/Fear lifecycle not modeled | Missing the Day 1→Day 7 emotional progression |
| **Fortunes-of-others** | Pity/Happy-for not modeled | Can't express "I imagine your daughter will be upset" |
| **OCEAN → PAD** | CognitiveDerivationEngine derives PersonalityWeights but doesn't compute PAD baseline from JPAF | Personality doesn't calibrate emotional intensity |

### 5.3 How Our Components Map to ALMA

```
┌─────────────────────────────────────────────────────────────┐
│ ALMA Layer 3: PERSONALITY                                   │
│                                                             │
│ eidos JPAF ──→ CognitiveDerivationEngine ──→ CognitiveDefaults│
│     │                                          │             │
│     └──→ OCEAN → PAD baseline (Mehrabian)      │             │
│                    ↓                            │             │
│              MoodBaseline (decay target)        │             │
├─────────────────────────────────────────────────────────────┤
│ ALMA Layer 2: MOOD                                          │
│                                                             │
│ MoodOrchestrator ←── MoodSignal (interaction appraisal)     │
│     │                                                       │
│     ├── MoodState (pleasure, arousal, dominance)            │
│     ├── MoodDecay (exponential toward MoodBaseline)         │
│     └── MoodPromptSection (renders mood to LLM)            │
│                                                             │
│ AffectTrajectoryAnalyzer (slope, volatility, trend)         │
├─────────────────────────────────────────────────────────────┤
│ ALMA Layer 1: EMOTION  ◄── THIS IS THE GAP                 │
│                                                             │
│ GoalAffectPhase writes raw PAD to goal nodes                │
│     └── Missing: OCC appraisal → typed emotion → PAD       │
│                                                             │
│ Need: CognitiveEmotion(type, intensity, subject, pad)       │
│       GoalAppraisal SPI (SEC-inspired)                      │
│       Emotion → Mood bridge (push emotions to MoodOrchestrator)│
└─────────────────────────────────────────────────────────────┘
```

---

## 6. Integration Path

### 6.1 Phase 1: OCC Foundation (blocks#296)

**Goal:** Introduce the OCC emotion type system and wire cognitive goals.

**New types in cognitive-api:**

```java
public enum EmotionType {
    // Prospect-based (goal lifecycle) — the core subset
    HOPE, FEAR, SATISFACTION, DISAPPOINTMENT, RELIEF, FEARS_CONFIRMED,
    // Well-being
    JOY, DISTRESS,
    // Fortunes of others
    HAPPY_FOR, PITY,
    // Attribution
    PRIDE, SHAME, REPROACH, ADMIRATION,
    // Compound
    GRATITUDE, ANGER, REMORSE, GRATIFICATION,
    // Object-based (future)
    LOVE, HATE,
    // Fortunes-of-others (disliked — future)
    RESENTMENT, GLOATING
}

public record CognitiveEmotion(
    EmotionType type,
    double intensity,       // [0,1] from appraisal
    String subjectId,       // goal node ID, agent ID, etc.
    Instant onset,
    PadProjection pad       // derived via ALMA table × personality
) {}

public record PadProjection(double pleasure, double arousal, double dominance) {}
```

**New in neocortex (mindmap-intelligence):**

```java
@FunctionalInterface
public interface GoalAppraisal {
    List<CognitiveEmotion> appraise(MindMapNode goal, AppraisalContext context);
}

public record AppraisalContext(
    String tenantId,
    PersonalityWeights personality,
    PadProjection padBaseline,
    int surfacingCount,
    Instant lastProgressAt,
    Map<String, Double> relationshipScores  // for fortunes-of-others
) {}
```

**Heuristic implementation (replaceable by ONNX later):**

```java
public class HeuristicGoalAppraisal implements GoalAppraisal {
    // Maps goal state → OCC emotion type + intensity using:
    // - priority → desirability
    // - feasibility + urgency trend → likelihood
    // - dynamic urgency from target-date → proximity
    // - surfacing-progress gap → effort/frustration
    // - agency (can the agent act?) → coping potential
    // - status (active/blocked/completed/abandoned) → sense of reality
}
```

**New in blocks:**

```java
public class CognitiveGoalOrchestrator {
    // Queries MindMap GOAL nodes
    // Runs GoalAppraisal to produce CognitiveEmotions per goal
    // Records surfacing via ExperienceRecorder
    // Checks case creation threshold
    // Produces GoalRevision recommendations for decay-signal
    // Caches CognitiveGoalState for prompt rendering
}
```

### 6.2 Phase 2: Emotion → Mood Bridge (follow-up)

Wire goal-level emotions into the agent's overall mood:
- After CognitiveGoalOrchestrator.tick(), aggregate emotions across goals
- Feed strongest/accumulated emotions to MoodOrchestrator via MoodSignal
- This creates the "I've been feeling anxious" whole-agent mood shift

### 6.3 Phase 3: Personality-Prior + Scenario Calibration (follow-up)

Refine HeuristicGoalAppraisal weights per agent via two layers:

**Layer 1 — Personality priors (automatic):**
Extend CognitiveDerivationEngine to derive appraisal weights from JPAF type.
Known correlations:
- J types: urgency amplifies Fear faster (deadline-sensitive)
- P types: urgency amplifies Fear slower (ambiguity-tolerant)
- Fe types: Pity/Happy-for amplified (relational harmony)
- Ti types: analytical distance, lower relationship weight
- Ni types: earlier Fear onset (anticipates consequences)
- Se types: later Fear onset, higher intensity when deadline arrives
- Fi types: deeper Remorse/Shame (personal values)
- Si types: Disappointment amplified by precedent violation

**Layer 2 — Scenario calibration (interactive):**
Present 15-20 goal scenarios with varying parameters. Agent/user rates
emotion type and intensity. Results stored in cognitive profile YAML
(hot-reloaded via CognitiveProfileWatcher). 20 scenarios is sufficient
to calibrate the key appraisal weights away from personality defaults.

**Layer 3 — Interaction refinement (ongoing, future):**
Real interaction feedback adjusts calibration — similar to
PersonalityEvolutionOrchestrator's bounded drift via TraitPressureSource.

**Why not ONNX:** Available datasets (ISEAR, GoEmotions, EmoBank) map
text → emotion, but goal appraisal input is structured numeric features
(priority, urgency, feasibility, surfacing count). No dataset maps these
structured features to OCC emotions. Synthetic data just learns the
heuristic rules. Personalized calibration is both more practical and
produces better results than training on generic cross-cultural averages.

### 6.4 Phase 4: Full OCC Coverage (future)

Extend beyond prospect-based emotions to:
- Agent-based (Pride, Shame, Admiration, Reproach) — requires action attribution
- Object-based (Love, Hate) — requires entity attitude tracking
- Compound (Gratitude, Anger, Remorse, Gratification) — requires event+agent attribution
- Full fortunes-of-others (Resentment, Gloating) — requires disliking relationships

### 6.5 Phase 5: Mood Congruence (future)

Implement mood congruence: current mood biases subsequent appraisals.
An anxious agent appraises ambiguous events more negatively (Fear threshold
lower, Hope threshold higher). This creates bounded positive feedback loops
that produce natural emotional escalation patterns.

---

## 7. Key Mappings Reference

### 7.1 OCC Appraisal Variables → Our System Properties

| OCC Variable | Our property | Source |
|-------------|-------------|--------|
| Desirability | `priority` | GoalPrioritizationPhase (neocortex) |
| Likelihood | `feasibility` + trend | GoalResolutionPhase (neocortex) |
| Proximity | Dynamic urgency from `target-date` | GoalUrgency (neocortex) |
| Unexpectedness | Delta from previous state | Computed at appraisal time |
| Sense of reality | `status` (active vs speculative) | MindMap node property |
| Effort invested | `surfaced-count` + progress events | SurfacingAggregationPhase (neocortex) |
| Coping potential | Agency — can the agent act? | Context-dependent |
| Praiseworthiness | Agent action attribution | ExperienceEvent source |
| Appealingness | Relationship liking score | RelationshipEvent + QualitySignal |

### 7.2 OCEAN → PAD (Gebhard/Mehrabian Equations)

```
P = 0.21E + 0.59A + 0.19N
A = 0.15O + 0.30A − 0.57N
D = 0.25O + 0.17C + 0.60E − 0.32A

Where: O=Openness, C=Conscientiousness, E=Extraversion, A=Agreeableness, N=Neuroticism
       (all standardized, range [-1, +1])
```

### 7.3 Personality-Modulated Emotion Intensity

```
base_intensity = Σ(w_i × appraisal_i) - threshold
personality_factor = f(OCEAN traits)  // e.g., high Neuroticism → lower threshold
modulated_intensity = clamp(base_intensity × personality_factor, 0, 1)
pad = ALMA_TABLE[emotion_type] × modulated_intensity
```

### 7.4 Emotion → Mood Accumulation (ALMA dynamics)

```
mood(t+1) = mood(t)
           + α × (emotion_pad - mood(t))     // emotion influence
           - β × (mood(t) - baseline)          // baseline decay
           
α ≈ 0.10  // emotion pushes mood 10% of distance per cycle
β ≈ 0.01  // mood decays 1% toward baseline per cycle
```

### 7.5 Goal Surfacing → Emotional Progression

```
Day 1: goal surfaced → Hope(0.7) + Fear(0.2)
  appraisal: desirability=high, likelihood=favorable, proximity=low, coping=low
  
Day 3: surfaced 3×, no progress → Hope(0.4) + Fear(0.5)
  appraisal: likelihood declining, proximity increasing, effort accumulating
  
Day 6: surfaced 6×, no progress → Fear(0.8) + Pity(0.6)
  appraisal: likelihood=low, proximity=high, coping=none, liked-other at risk
  
Day 7: deadline → Fears-confirmed(0.9) + Pity(0.8) + Reproach(0.4)
  OR (if action taken): Relief(0.9) + Happy-for(0.7)
  
Post-miss: Remorse(0.6) — if agent blames itself for not being insistent enough
```

---

## 8. References

### Foundational Papers

- Ortony, A., Clore, G. L., & Collins, A. (1988). *The Cognitive Structure of Emotions*. Cambridge University Press.
- Ortony, A., Clore, G. L., & Collins, A. (2022). *The Cognitive Structure of Emotions* (2nd ed.). Cambridge University Press.
- Mehrabian, A. & Russell, J. A. (1974). *An Approach to Environmental Psychology*. MIT Press.
- Mehrabian, A. (1996). "Analysis of the Big-five personality factors in terms of the PAD Temperament Model." *Australian Journal of Psychology*, 48(2), 86–92.
- Scherer, K. R. (2001). "Appraisal considered as a process of multilevel sequential checking." In *Appraisal Processes in Emotion*, Oxford University Press.
- Scherer, K. R. (2009). "The dynamic architecture of emotion." *Cognition and Emotion*, 23(7), 1307–1351.
- Plutchik, R. (1980). *Emotion: A Psychoevolutionary Synthesis*. Harper & Row.

### Computational Architectures

- Gebhard, P. (2005). "ALMA — A Layered Model of Affect." *AAMAS 2005*. [PDF](https://alma.dfki.de/papers/aamas05.pdf)
- Gebhard, P. & Kipp, K. H. (2006). "Are computer-generated emotions and moods plausible to humans?" *IVA 2006*. [PDF](https://alma.dfki.de/papers/iva06.pdf)
- Becker-Asano, C. (2008). *WASABI: Affect Simulation for Agents with Believable Interactivity*. PhD Thesis, University of Bielefeld. [Springer](https://link.springer.com/article/10.1007/s10458-009-9094-9)
- Dias, J. et al. (2022). "FAtiMA Toolkit." *ACM TIIS*, 12(2). [ACM](https://dl.acm.org/doi/full/10.1145/3510822)
- Marsella, S. & Gratch, J. (2009). "EMA: A process model of appraisal dynamics." *Cognitive Systems Research*, 10(1), 70–90.
- Marsella, S., Gratch, J., & Petta, P. (2010). "Computational Models of Emotion." In *A Blueprint for Affective Computing*. [PDF](https://people.ict.usc.edu/gratch/public_html/papers/MarGraPet_Review.pdf)

### Formal Specifications

- Steunebrink, B. R., Dastani, M., & Meyer, J.-J. Ch. (2009). "The OCC Model Revisited." *KI 2009*. [PDF](https://people.idsia.ch/~steunebrink/Publications/KI09_OCC_revisited.pdf)
- Adam, C. & Longin, D. (2024). "On the logic of agent's emotions." *Cognitive Systems Research*. [ScienceDirect](https://www.sciencedirect.com/science/article/abs/pii/S1389041724000755)

### Mappings and Calibration

- Limbrecht-Ecklundt, K. et al. (2013). "Pleasure, Arousal, Dominance: Mehrabian and Russell revisited." [ResearchGate](https://www.researchgate.net/publication/265439455_Pleasure_Arousal_Dominance_Mehrabian_and_Russell_revisited)
- Scherer, K. R. (1997). "Profiles of Emotion-antecedent Appraisal: Testing Theoretical Predictions across Cultures." *JPSP*, 73(5), 902–922.
- Bartneck, C. (2002). "Integrating the OCC model of emotions in embodied characters." [PDF](https://www.bartneck.de/publications/2002/integratingTheOCCModel/bartneckHF2002.pdf)

### Datasets

- Scherer, K. R. & Wallbott, H. G. (1994). ISEAR. [UNIGE CISA](https://www.unige.ch/cisa/research/materials-and-online-research/research-material). [Python loader](https://github.com/sinmaniphel/py_isear_dataset)
- Demszky, D. et al. (2020). "GoEmotions: A Dataset of Fine-Grained Emotions." *ACL 2020*. [Google Research](https://research.google/blog/goemotions-a-dataset-for-fine-grained-emotion-classification/)
- Buechel, S. & Hahn, U. (2017). "EmoBank." [Kaggle](https://www.kaggle.com/datasets/jackksoncsie/emobank)
- Lang, P. J. et al. (2005). IAPS. CSEA, University of Florida. [Wikipedia](https://en.wikipedia.org/wiki/International_Affective_Picture_System)
- Bradley, M. M. & Lang, P. J. (1999). ANEW. [Springer](https://link.springer.com/content/pdf/10.3758/bf03193164.pdf)
- Mohammad, S. (2018). NRC VAD Lexicon. [NRC](https://saifmohammad.com/WebPages/nrc-vad.html)
- Kurdi, B. et al. (2017). OASIS. Harvard. [Frontiers](https://www.frontiersin.org/journals/psychology/articles/10.3389/fpsyg.2020.02187/full)

### Surveys

- Bourgais, M. et al. (2018). "Emotion Modeling in Social Simulation: A Survey." *JASSS*, 21(2). [Link](https://www.jasss.org/21/2/5.html)
- Rodríguez, L. F. & Ramos, F. (2016). "Computational Approaches to Modeling Artificial Emotion." *Frontiers in Robotics and AI*. [Link](https://www.frontiersin.org/journals/robotics-and-ai/articles/10.3389/frobt.2016.00021/full)
- Adam, C. et al. (2019). "Designing emotional BDI agents: good practices and open questions." *KER*. [Cambridge](https://www.cambridge.org/core/journals/knowledge-engineering-review/article/designing-emotional-bdi-agents-good-practices-and-open-questions/4915E98A3A0A1BEAEFFB219FA5E79C9D)
- Scherer, K. R. (2005). "What are emotions? And how can they be measured?" *Social Science Information*, 44(4), 695–729.

### Temporal Dynamics

- Verduyn, P. & Lavrijsen, S. (2015). "Which emotions last longest and why." *Motivation and Emotion*, 39(1), 119–127.
- Thornton, M. A. & Tamir, D. I. (2017). "Mental models accurately predict emotion transitions." *PNAS*, 114(23), 5982–5987. [Link](https://pnas.org/content/114/23/5982)
- Eloy, L. et al. (2024). "Simulating Emotions with an Integrated Computational Model of Appraisal and Reinforcement Learning." *CHI 2024*. [ACM](https://dl.acm.org/doi/10.1145/3613904.3641908)
