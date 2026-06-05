package io.casehub.inference.runtime;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.util.Map;

public final class RawInference {

    private RawInference() {}

    public static float[] classifyPair(OrtEnvironment env, OrtSession session,
                                        HuggingFaceTokenizer tokenizer,
                                        String premise, String hypothesis) throws OrtException {
        Encoding encoding = tokenizer.encode(premise, hypothesis);
        long[] inputIds = encoding.getIds();
        long[] attentionMask = encoding.getAttentionMask();

        long[][] inputIds2d = {inputIds};
        long[][] attentionMask2d = {attentionMask};

        try (OnnxTensor idsTensor = OnnxTensor.createTensor(env, inputIds2d);
             OnnxTensor maskTensor = OnnxTensor.createTensor(env, attentionMask2d)) {

            Map<String, OnnxTensor> inputs = Map.of(
                "input_ids", idsTensor,
                "attention_mask", maskTensor
            );

            try (OrtSession.Result result = session.run(inputs)) {
                float[][] logits = (float[][]) result.get(0).getValue();
                return logits[0];
            }
        }
    }
}
