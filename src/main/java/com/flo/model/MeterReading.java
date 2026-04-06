package com.flo.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MeterReading(String nmi, LocalDateTime timestamp, BigDecimal consumption) {

}

