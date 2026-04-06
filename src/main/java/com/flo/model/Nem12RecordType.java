package com.flo.model;

/**
 * NEM12 record type identifiers as defined in the NEM12 specification.
 * Using an enum prevents magic string literals scattered through the parser
 * and makes the code self-documenting.
 */
public enum Nem12RecordType {

    HEADER("100"),
    NMI_DATA_DETAILS("200"),
    INTERVAL_DATA("300"),
    INTERVAL_EVENT("400"),
    B2B_DETAILS("500"),
    END("900");

    private final String code;

    Nem12RecordType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public boolean matches(String value) {
        return code.equals(value);
    }
}

