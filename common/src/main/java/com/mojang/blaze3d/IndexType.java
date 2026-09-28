package com.mojang.blaze3d;

/**
 * Compatibility shim for MC 26.1, where IndexType was not yet a top-level class.
 * In MC 26.2+, the real com.mojang.blaze3d.IndexType exists and this shim is shadowed.
 *
 * Note: In 26.1, VertexFormat.IndexType had SHORT(2) and INT(4).
 */
public class IndexType {
    public static final IndexType SHORT = new IndexType(2);
    public static final IndexType INT   = new IndexType(4);

    public final int bytes;

    private IndexType(int bytes) {
        this.bytes = bytes;
    }
}
