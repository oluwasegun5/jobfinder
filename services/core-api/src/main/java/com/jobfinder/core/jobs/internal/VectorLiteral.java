package com.jobfinder.core.jobs.internal;

/** pgvector's text form of a vector, {@code [0.1,0.2]}, for binding as {@code cast(:v as vector)}. */
final class VectorLiteral {

    private VectorLiteral() {
    }

    static String of(float[] values) {
        StringBuilder text = new StringBuilder(values.length * 10).append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(values[i]);
        }
        return text.append(']').toString();
    }
}
