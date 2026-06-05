package io.casehub.inference.runtime;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.nio.file.Path;

public final class OnnxSessionLoader {

    private OnnxSessionLoader() {}

    public static OrtEnvironment createEnvironment() {
        return OrtEnvironment.getEnvironment();
    }

    public static OrtSession createSession(OrtEnvironment env, Path modelPath) throws OrtException {
        return env.createSession(modelPath.toString());
    }
}
