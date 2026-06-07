package io.casehub.inference.runtime;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.TensorInfo;
import io.casehub.inference.InferenceException;
import io.casehub.inference.InferenceInput;
import io.casehub.inference.InferenceModel;
import io.casehub.inference.InferenceOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
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
    private final OptionalInt outputSize;
    private volatile boolean closed;

    /**
     * Creates a new model from the given configuration. Loads the ONNX model,
     * validates its inputs (must have {@code input_ids} and {@code attention_mask}),
     * validates its outputs (at least one, rank 2), and creates the tokenizer.
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

            // Validate outputs: at least one, rank 2
            Map<String, NodeInfo> outputInfo = session.getOutputInfo();
            if (outputInfo.isEmpty()) {
                throw new ModelLoadException("Model must have at least one output");
            }
            NodeInfo firstOutput = outputInfo.values().iterator().next();
            if (!(firstOutput.getInfo() instanceof TensorInfo tensorInfo)) {
                throw new ModelLoadException("First output must be a tensor");
            }
            long[] shape = tensorInfo.getShape();
            if (shape.length != 2) {
                throw new ModelLoadException(
                    "Output must be rank 2 [batch, values], got rank " + shape.length);
            }
            // shape[1] == -1 means dynamic dimension
            this.outputSize = shape[1] >= 0
                ? OptionalInt.of((int) shape[1])
                : OptionalInt.empty();

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

        try (OnnxTensor idsTensor = OnnxTensor.createTensor(env, inputIds2d);
             OnnxTensor maskTensor = OnnxTensor.createTensor(env, attentionMask2d);
             OrtSession.Result result = session.run(
                 Map.of("input_ids", idsTensor, "attention_mask", maskTensor))) {

            float[][] logits = (float[][]) result.get(0).getValue();
            return new InferenceOutput(logits[0]);

        } catch (OrtException e) {
            throw new InferenceException("Inference failed: " + e.getMessage(), e);
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
        // Zero-fill is correct: [PAD]=0 for BERT family, attention_mask=0 means "don't attend"
        long[][] batchIds = new long[batchSize][maxLen];
        long[][] batchMask = new long[batchSize][maxLen];
        for (int i = 0; i < batchSize; i++) {
            long[] ids = encodings[i].getIds();
            long[] mask = encodings[i].getAttentionMask();
            System.arraycopy(ids, 0, batchIds[i], 0, ids.length);
            System.arraycopy(mask, 0, batchMask[i], 0, mask.length);
            // remaining positions are already 0 (long[] default)
        }

        try (OnnxTensor idsTensor = OnnxTensor.createTensor(env, batchIds);
             OnnxTensor maskTensor = OnnxTensor.createTensor(env, batchMask);
             OrtSession.Result result = session.run(
                 Map.of("input_ids", idsTensor, "attention_mask", maskTensor))) {

            float[][] logits = (float[][]) result.get(0).getValue();
            List<InferenceOutput> outputs = new ArrayList<>(batchSize);
            for (int i = 0; i < batchSize; i++) {
                outputs.add(new InferenceOutput(logits[i]));
            }
            return Collections.unmodifiableList(outputs);

        } catch (OrtException e) {
            throw new InferenceException("Batch inference failed: " + e.getMessage(), e);
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
