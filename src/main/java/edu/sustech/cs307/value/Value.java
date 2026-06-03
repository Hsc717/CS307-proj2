package edu.sustech.cs307.value;

import edu.sustech.cs307.exception.DBException;

import java.nio.ByteBuffer;

public class Value implements Comparable<Value> {
    public Object value;
    public ValueType type;
    public static final int INT_SIZE = 8;
    public static final int FLOAT_SIZE = 8;
    public static final int DOUBLE_SIZE = 8;
    public static final int CHAR_SIZE = 64;
    public static final int VARCHAR_DEFAULT_SIZE = 255;

    /** Column-specific length for VARCHAR(N), set by InsertOperator/UpdateOperator */
    public int columnLen = -1;

    public Value(Object value, ValueType type) {
        this.value = value;
        this.type = type;
    }

    public Value(Long value) {
        this.value = value;
        type = ValueType.INTEGER;
    }

    public Value(Double value) {
        this.value = value;
        type = ValueType.FLOAT;
    }

    public Value(Double value, ValueType type) {
        this.value = value;
        this.type = type;
    }

    public Value(String value) {
        this.value = value;
        type = ValueType.CHAR;
    }

    public Value(String value, ValueType type) {
        this.value = value;
        this.type = type;
    }

    @Override
    public int compareTo(Value other) {
        try {
            return ValueComparer.compare(this, other);
        } catch (DBException e) {
            return 0;
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Value other = (Value) obj;
        try {
            return ValueComparer.compare(this, other) == 0;
        } catch (DBException e) {
            return false;
        }
    }

    @Override
    public int hashCode() {
        String str = toString();
        return str != null ? str.hashCode() : 0;
    }

    /**
     * Convert value to byte array using the default size for its type.
     * For VARCHAR, uses VARCHAR_DEFAULT_SIZE unless columnLen is set.
     */
    public byte[] ToByte() {
        return switch (type) {
            case INTEGER -> {
                ByteBuffer buf = ByteBuffer.allocate(INT_SIZE);
                buf.putLong((long) value);
                yield buf.array();
            }
            case FLOAT, DOUBLE -> {
                ByteBuffer buf = ByteBuffer.allocate(DOUBLE_SIZE);
                buf.putDouble((double) value);
                yield buf.array();
            }
            case CHAR -> {
                String str = (String) value;
                byte[] bytes = str.getBytes();
                byte[] result = new byte[CHAR_SIZE];
                System.arraycopy(bytes, 0, result, 0, Math.min(bytes.length, CHAR_SIZE));
                yield result;
            }
            case VARCHAR -> {
                String str = (String) value;
                int len = (columnLen > 0) ? columnLen : VARCHAR_DEFAULT_SIZE;
                byte[] bytes = str.getBytes();
                byte[] result = new byte[len];
                System.arraycopy(bytes, 0, result, 0, Math.min(bytes.length, len));
                yield result;
            }
            default -> throw new RuntimeException("Unsupported value type: " + type);
        };
    }

    /**
     * Convert value to byte array with a specific column length.
     */
    public byte[] ToByte(int length) {
        return switch (type) {
            case INTEGER -> {
                ByteBuffer buf = ByteBuffer.allocate(INT_SIZE);
                buf.putLong((long) value);
                yield buf.array();
            }
            case FLOAT, DOUBLE -> {
                ByteBuffer buf = ByteBuffer.allocate(DOUBLE_SIZE);
                buf.putDouble((double) value);
                yield buf.array();
            }
            case CHAR, VARCHAR -> {
                String str = (String) value;
                byte[] bytes = str.getBytes();
                byte[] result = new byte[length];
                System.arraycopy(bytes, 0, result, 0, Math.min(bytes.length, length));
                yield result;
            }
            default -> throw new RuntimeException("Unsupported value type: " + type);
        };
    }

    /**
     * Create a Value from a byte array.
     */
    public static Value FromByte(byte[] bytes, ValueType type) {
        return switch (type) {
            case INTEGER -> {
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                yield new Value(buf.getLong());
            }
            case FLOAT -> {
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                yield new Value(buf.getDouble(), ValueType.FLOAT);
            }
            case DOUBLE -> {
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                yield new Value(buf.getDouble(), ValueType.DOUBLE);
            }
            case CHAR -> {
                String s = new String(bytes).trim();
                yield new Value(s);
            }
            case VARCHAR -> {
                String s = new String(bytes).trim();
                yield new Value(s, ValueType.VARCHAR);
            }
            default -> throw new RuntimeException("Unsupported value type: " + type);
        };
    }

    @Override
    public String toString() {
        return switch (type) {
            case INTEGER -> value.toString();
            case FLOAT, DOUBLE -> value.toString();
            case CHAR, VARCHAR -> ((String) value).trim();
            default -> throw new RuntimeException("Unsupported value type: " + type);
        };
    }
}