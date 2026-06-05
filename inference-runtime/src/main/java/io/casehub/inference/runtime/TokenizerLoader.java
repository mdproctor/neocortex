package io.casehub.inference.runtime;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import java.io.IOException;
import java.nio.file.Path;

public final class TokenizerLoader {

    private TokenizerLoader() {}

    public static HuggingFaceTokenizer load(Path tokenizerJsonPath) throws IOException {
        return HuggingFaceTokenizer.newInstance(tokenizerJsonPath);
    }
}
