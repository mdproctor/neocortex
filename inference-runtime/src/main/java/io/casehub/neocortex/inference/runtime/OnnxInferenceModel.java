package io.casehub.neocortex.inference.runtime;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import io.casehub.neocortex.inference.InferenceException;
import io.casehub.neocortex.inference.InferenceInput;
import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.InferenceOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * ONNX Runtime implementation of {@link InferenceModel}. Wraps ONNX Runtime JNI
 * for model execution and DJL HuggingFace Tokenizers JNI for tokenization.
 *
 * <p>Thread-safe for concurrent {@link #run}/{@link #runBatch} calls once constructed.
 * One-shot lifecycle: construct, use, close.
 */
public final class OnnxInferenceModel implements InferenceModel {

    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final boolean requiresTokenTypeIds;
    private final OptionalInt outputSize;
    private final boolean hasRank3Output;
    private volatile boolean closed;

    /**
     * Creates a new model from the given configuration. Loads the ONNX model,
     * validates its inputs (must have {@code input_ids} and {@code attention_mask}),
     * validates its outputs (at least one, each rank 2 or 3), and creates the tokenizer.
     *
     * <p>Supports multi-output models (e.g., BGE-M3 with dense, sparse, and ColBERT heads)
     * and rank-3 {@code [batch, seq_len, dim]} outputs (e.g., ColBERT token-level embeddings).
     *
     * @throws ModelLoadException if the model or tokenizer cannot be loaded,
     *         or if the model's input/output schema is invalid
     */
    public OnnxInferenceModel(ModelConfig config) {
        OrtSession openedSession = null;
        HuggingFaceTokenizer openedTokenizer = null;

        try {
            this.env = OrtEnvironment.getEnvironment();

            // Session options with thread config
            try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
                if (config.intraOpThreads() > 0) {
                    opts.setIntraOpNumThreads(config.intraOpThreads());
                }
                if (config.interOpThreads() > 0) {
                    opts.setInterOpNumThreads(config.interOpThreads());
                }
                openedSession = env.createSession(config.modelPath().toString(), opts);
            }
            this.session = openedSession;

            // Validate inputs: must have input_ids and attention_mask
            Set<String> inputNames = session.getInputNames();
            if (!inputNames.contains("input_ids")) {
                throw new ModelLoadException(
                    "Model must have 'input_ids' input, found: " + inputNames);
            }
            if (!inputNames.contains("attention_mask")) {
                throw new ModelLoadException(
                    "Model must have 'attention_mask' input, found: " + inputNames);
            }
            this.requiresTokenTypeIds = inputNames.contains("token_type_ids");

            // Validate outputs: at least one, each must be rank 2 or rank 3
            Map<String, NodeInfo> outputInfo = session.getOutputInfo();
            if (outputInfo.isEmpty()) {
                throw new ModelLoadException("Model must have at least one output");
            }
            boolean anyRank3 = false;
            for (Map.Entry<String, NodeInfo> entry : outputInfo.entrySet()) {
                if (!(entry.getValue().getInfo() instanceof TensorInfo ti)) {
                    throw new ModelLoadException(
                        "Output '" + entry.getKey() + "' must be a tensor");
                }
                int rank = ti.getShape().length;
                if (rank < 2 || rank > 3) {
                    throw new ModelLoadException(
                        "Output '" + entry.getKey() + "' must be rank 2 or 3, got rank " + rank);
                }
                if (rank == 3) anyRank3 = true;
            }
            this.hasRank3Output = anyRank3;

            // outputSize: only meaningful for single-output rank-2 models with known dimension
            if (outputInfo.size() == 1 && !anyRank3) {
                TensorInfo tensorInfo = (TensorInfo) outputInfo.values().iterator().next().getInfo();
                long[] shape = tensorInfo.getShape();
                this.outputSize = shape[1] >= 0
                    ? OptionalInt.of((int) shape[1])
                    : OptionalInt.empty();
            } else {
                this.outputSize = OptionalInt.empty();
            }

            // Create tokenizer with truncation, no padding
            openedTokenizer = HuggingFaceTokenizer.newInstance(
                config.tokenizerPath(),
                Map.of("maxLength", String.valueOf(config.maxSequenceLength()),
                       "truncation", "true",
                       "padding", "false"));
            this.tokenizer = openedTokenizer;

        } catch (ModelLoadException e) {
            // Clean up already-opened resources on failure
            closeQuietly(openedSession);
            closeQuietly(openedTokenizer);
            throw e;
        } catch (OrtException e) {
            closeQuietly(openedSession);
            closeQuietly(openedTokenizer);
            throw new ModelLoadException("Failed to load ONNX model: " + e.getMessage(), e);
        } catch (IOException e) {
            closeQuietly(openedSession);
            closeQuietly(openedTokenizer);
            throw new ModelLoadException("Failed to load tokenizer: " + e.getMessage(), e);
        }
    }

    @Override
    public InferenceOutput run(InferenceInput input) {
        if (closed) throw new InferenceException("Model is closed");
        Objects.requireNonNull(input, "input must not be null");

        List<String> texts = input.texts();
        Encoding encoding = texts.size() == 1
            ? tokenizer.encode(texts.get(0))
            : tokenizer.encode(texts.get(0), texts.get(1));

        long[] inputIds = encoding.getIds();
        long[] attentionMask = encoding.getAttentionMask();

        long[][] inputIds2d = {inputIds};
        long[][] attentionMask2d = {attentionMask};

        List<OnnxTensor> tensors = new ArrayList<>();
        try {
            OnnxTensor idsTensor = OnnxTensor.createTensor(env, inputIds2d);
            tensors.add(idsTensor);
            OnnxTensor maskTensor = OnnxTensor.createTensor(env, attentionMask2d);
            tensors.add(maskTensor);

            Map<String, OnnxTensor> inputMap = new HashMap<>();
            inputMap.put("input_ids", idsTensor);
            inputMap.put("attention_mask", maskTensor);

            if (requiresTokenTypeIds) {
                long[][] typeIds2d = {encoding.getTypeIds()};
                OnnxTensor typeIdsTensor = OnnxTensor.createTensor(env, typeIds2d);
                tensors.add(typeIdsTensor);
                inputMap.put("token_type_ids", typeIdsTensor);
            }

            try (OrtSession.Result result = session.run(inputMap)) {
                Map<String, float[][]> outputs = new LinkedHashMap<>();
                for (Map.Entry<String, OnnxValue> entry : result) {
                    Object value = entry.getValue().getValue();
                    if (value instanceof float[][] rank2) {
                        outputs.put(entry.getKey(), new float[][] { rank2[0] });
                    } else if (value instanceof float[][][] rank3) {
                        outputs.put(entry.getKey(), rank3[0]);
                    }
                }
                return new InferenceOutput(outputs);
            }
        } catch (OrtException e) {
            throw new InferenceException("Inference failed: " + e.getMessage(), e);
        } finally {
            for (OnnxTensor t : tensors) {
                t.close();
            }
        }
    }

    @Override
    public List<InferenceOutput> runBatch(List<InferenceInput> inputs) {
        if (closed) throw new InferenceException("Model is closed");

        if (inputs == null) {
            throw new IllegalArgumentException("inputs must not be null");
        }
        if (inputs.isEmpty()) return List.of();

        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i) == null) {
                throw new IllegalArgumentException("inputs[" + i + "] must not be null");
            }
        }

        int batchSize = inputs.size();

        // Tokenize all inputs
        Encoding[] encodings = new Encoding[batchSize];
        int maxLen = 0;
        for (int i = 0; i < batchSize; i++) {
            List<String> texts = inputs.get(i).texts();
            encodings[i] = texts.size() == 1
                ? tokenizer.encode(texts.get(0))
                : tokenizer.encode(texts.get(0), texts.get(1));
            maxLen = Math.max(maxLen, encodings[i].getIds().length);
        }

        // Pad to batch-max length and stack into 2D arrays
        // Zero-fill is correct: [PAD]=0 for BERT family, attention_mask=0 means "don't attend",
        // token_type_ids=0 means segment A
        long[][] batchIds = new long[batchSize][maxLen];
        long[][] batchMask = new long[batchSize][maxLen];
        long[][] batchTypeIds = requiresTokenTypeIds ? new long[batchSize][maxLen] : null;
        for (int i = 0; i < batchSize; i++) {
            long[] ids = encodings[i].getIds();
            long[] mask = encodings[i].getAttentionMask();
            System.arraycopy(ids, 0, batchIds[i], 0, ids.length);
            System.arraycopy(mask, 0, batchMask[i], 0, mask.length);
            if (batchTypeIds != null) {
                long[] typeIds = encodings[i].getTypeIds();
                System.arraycopy(typeIds, 0, batchTypeIds[i], 0, typeIds.length);
            }
        }

        List<OnnxTensor> tensors = new ArrayList<>();
        try {
            OnnxTensor idsTensor = OnnxTensor.createTensor(env, batchIds);
            tensors.add(idsTensor);
            OnnxTensor maskTensor = OnnxTensor.createTensor(env, batchMask);
            tensors.add(maskTensor);

            Map<String, OnnxTensor> inputMap = new HashMap<>();
            inputMap.put("input_ids", idsTensor);
            inputMap.put("attention_mask", maskTensor);

            if (batchTypeIds != null) {
                OnnxTensor typeIdsTensor = OnnxTensor.createTensor(env, batchTypeIds);
                tensors.add(typeIdsTensor);
                inputMap.put("token_type_ids", typeIdsTensor);
            }

            try (OrtSession.Result result = session.run(inputMap)) {
                // Extract all named outputs from the session result
                Map<String, Object> rawOutputs = new LinkedHashMap<>();
                for (Map.Entry<String, OnnxValue> entry : result) {
                    rawOutputs.put(entry.getKey(), entry.getValue().getValue());
                }

                // Build per-sample InferenceOutput from all outputs
                List<InferenceOutput> outputs = new ArrayList<>(batchSize);
                for (int i = 0; i < batchSize; i++) {
                    Map<String, float[][]> sampleOutputs = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : rawOutputs.entrySet()) {
                        Object value = entry.getValue();
                        if (value instanceof float[][] rank2) {
                            sampleOutputs.put(entry.getKey(), new float[][] { rank2[i] });
                        } else if (value instanceof float[][][] rank3) {
                            // Strip padding vectors using attention mask
                            int actualLen = 0;
                            for (long v : batchMask[i]) actualLen += (int) v;
                            float[][] stripped = Arrays.copyOf(rank3[i], actualLen);
                            sampleOutputs.put(entry.getKey(), stripped);
                        }
                    }
                    outputs.add(new InferenceOutput(sampleOutputs));
                }
                return Collections.unmodifiableList(outputs);
            }
        } catch (OrtException e) {
            throw new InferenceException("Batch inference failed: " + e.getMessage(), e);
        } finally {
            for (OnnxTensor t : tensors) {
                t.close();
            }
        }
    }

    @Override
    public OptionalInt outputSize() {
        return outputSize;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            session.close();
        } catch (Exception ignored) {
            // swallow — close must not throw
        }
        try {
            tokenizer.close();
        } catch (Exception ignored) {
            // swallow — close must not throw
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // swallow
            }
        }
    }
}
