package edu.sustech.cs307.value;

public enum ValueType {
    CHAR,
    VARCHAR,
    INTEGER,
    FLOAT,
    DOUBLE,
    UNKNOWN;

    @Override
    public String toString() {
        return switch (this) {
            case CHAR -> "char";
            case VARCHAR -> "varchar";
            case INTEGER -> "int";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case UNKNOWN -> "unknown";
        };
    }
}