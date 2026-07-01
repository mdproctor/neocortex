package io.casehub.neocortex.inference;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InferenceOutputTest {

    @Test
    void singleOutputFactory() {
        InferenceOutput out = InferenceOutput.of(1f, 2f, 3f);
        assertArrayEquals(new float[]{1f, 2f, 3f}, out.values());
    }

    @Test
    void singleOutputDefensiveCopy() {
        float[] input = {1f, 2f};
        InferenceOutput out = InferenceOutput.of(input);
        input[0] = 999f;
        assertEquals(1f, out.values()[0]);
        out.values()[0] = 999f;
        assertEquals(1f, out.values()[0]);
    }

    @Test
    void multiOutputConstruction() {
        var outputs = Map.of(
            "dense", new float[][]{{1f, 2f}},
            "sparse", new float[][]{{3f, 4f, 5f}}
        );
        InferenceOutput out = new InferenceOutput(outputs);
        assertArrayEquals(new float[]{1f, 2f}, out.vector("dense"));
        assertArrayEquals(new float[]{3f, 4f, 5f}, out.vector("sparse"));
    }

    @Test
    void multiOutputDefensiveCopy() {
        float[][] data = {{1f, 2f}};
        var outputs = Map.of("a", data);
        InferenceOutput out = new InferenceOutput(outputs);
        data[0][0] = 999f;
        assertEquals(1f, out.vector("a")[0]);
    }

    @Test
    void valuesThrowsForMultiOutput() {
        var outputs = Map.of(
            "a", new float[][]{{1f}},
            "b", new float[][]{{2f}}
        );
        InferenceOutput out = new InferenceOutput(outputs);
        assertThrows(IllegalStateException.class, out::values);
    }

    @Test
    void outputThrowsForUnknownName() {
        InferenceOutput out = InferenceOutput.of(1f);
        assertThrows(IllegalArgumentException.class, () -> out.output("missing"));
    }

    @Test
    void rank2OutputPreserved() {
        float[][] colbert = {{1f, 2f}, {3f, 4f}, {5f, 6f}};
        var outputs = Map.of("colbert", colbert);
        InferenceOutput out = new InferenceOutput(outputs);
        float[][] result = out.output("colbert");
        assertEquals(3, result.length);
        assertArrayEquals(new float[]{3f, 4f}, result[1]);
    }

    @Test
    void outputNamesReturnsKeySet() {
        var outputs = Map.of(
            "dense", new float[][]{{1f}},
            "sparse", new float[][]{{2f}}
        );
        InferenceOutput out = new InferenceOutput(outputs);
        assertEquals(Set.of("dense", "sparse"), out.outputNames());
    }

    @Test
    void emptyOutputsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new InferenceOutput(Map.of()));
    }

    @Test
    void nullOutputsRejected() {
        assertThrows(NullPointerException.class,
            () -> new InferenceOutput(null));
    }

    @Test
    void equalityAndHashCode() {
        InferenceOutput a = InferenceOutput.of(1f, 2f);
        InferenceOutput b = InferenceOutput.of(1f, 2f);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        InferenceOutput c = InferenceOutput.of(1f, 3f);
        assertNotEquals(a, c);
    }

    @Test
    void outputDefensiveCopyOnAccess() {
        var outputs = Map.of("a", new float[][]{{1f, 2f}});
        InferenceOutput out = new InferenceOutput(outputs);
        float[][] returned = out.output("a");
        returned[0][0] = 999f;
        assertEquals(1f, out.output("a")[0][0]);
    }

    @Test
    void vectorDefensiveCopyOnAccess() {
        var outputs = Map.of("a", new float[][]{{1f, 2f}});
        InferenceOutput out = new InferenceOutput(outputs);
        float[] returned = out.vector("a");
        returned[0] = 999f;
        assertEquals(1f, out.vector("a")[0]);
    }

    @Test
    void outputNamesIsUnmodifiable() {
        var outputs = Map.of("a", new float[][]{{1f}});
        InferenceOutput out = new InferenceOutput(outputs);
        Set<String> names = out.outputNames();
        assertThrows(UnsupportedOperationException.class, () -> names.add("hacked"));
    }
}
