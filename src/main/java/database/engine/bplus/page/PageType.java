package database.engine.bplus.page;

/**
 * What a page holds, stored as one byte at offset 0.
 */
public enum PageType {

    LEAF(0),
    INTERNAL(1);

    private final int code;

    PageType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static PageType fromCode(int code) {
        return switch (code) {
            case 0 -> LEAF;
            case 1 -> INTERNAL;
            default -> throw new IllegalArgumentException("unknown page type code: " + code);
        };
    }
}
