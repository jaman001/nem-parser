package org.example.util;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * Utility to generate a large NEM12 test file.
 * <p>
 * Tweak the constants below to control file size:
 * NMI_COUNT          - number of unique NMI / 200 records
 * DAYS_PER_NMI       - number of 300 records (days) per NMI
 * INTERVAL_MINUTES   - interval length (e.g. 30 = 48 intervals per day)
 * OUTPUT_FILE        - path of the generated file
 */
public class Nem12TestFileGenerator {

    // -------------------------------------------------------------------------
    // Configuration — adjust these to control file size
    // -------------------------------------------------------------------------
    private static final int NMI_COUNT = 1_000;   // unique 200 records
    private static final int DAYS_PER_NMI = 365;     // 300 records per NMI
    private static final int INTERVAL_MINUTES = 30;      // 30 min → 48 values/day
    private static final String OUTPUT_FILE = "nem12_large_test.csv";
    // -------------------------------------------------------------------------

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final LocalDate START_DATE = LocalDate.of(2005, 1, 1);
    private static final String CREATED_DT = "20050310121004";
    private static final int INTERVALS = (24 * 60) / INTERVAL_MINUTES;

    public static void main(String[] args) throws IOException {

        long startMs = System.currentTimeMillis();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_FILE))) {
            writeHeader(writer);

            Random random = new Random(42); // fixed seed for reproducibility

            for (int nmiIndex = 0; nmiIndex < NMI_COUNT; nmiIndex++) {
                String nmi = generateNmi(nmiIndex);
                write200Record(writer, nmi);

                LocalDate date = START_DATE;
                for (int day = 0; day < DAYS_PER_NMI; day++) {
                    write300Record(writer, date, random);
                    date = date.plusDays(1);
                }

                write500Record(writer);
            }

            writeFooter(writer);
        }

        long elapsed = System.currentTimeMillis() - startMs;
        System.out.printf("Generated %s in %d ms%n", OUTPUT_FILE, elapsed);
        System.out.printf("NMIs: %,d | Days/NMI: %,d | Intervals/day: %d%n", NMI_COUNT, DAYS_PER_NMI, INTERVALS);
        System.out.printf("Total 300 records: %,d | Total interval rows: %,d%n", (long) NMI_COUNT * DAYS_PER_NMI, (long) NMI_COUNT * DAYS_PER_NMI * INTERVALS);
    }

    // -------------------------------------------------------------------------
    // Record writers
    // -------------------------------------------------------------------------

    private static void writeHeader(BufferedWriter w) throws IOException {

        w.write("100,NEM12,200506081149,UNITEDDP,NEMMCO");
        w.newLine();
    }

    private static void write200Record(BufferedWriter w, String nmi) throws IOException {
        // 200,<NMI>,E1E2,1,E1,N1,01009,kWh,<intervalMinutes>,20050610
        w.write(String.format("200,%s,E1E2,1,E1,N1,01009,kWh,%d,20050610", nmi, INTERVAL_MINUTES));
        w.newLine();
    }

    private static void write300Record(BufferedWriter w, LocalDate date, Random random) throws IOException {

        StringBuilder sb = new StringBuilder("300,");
        sb.append(date.format(DATE_FMT));

        for (int i = 0; i < INTERVALS; i++) {
            sb.append(',');
            sb.append(randomConsumption(random));
        }

        // quality flag, reason code, reason description, update datetime, MSN datetime
        sb.append(",A,,,")
          .append(CREATED_DT)
          .append(',');

        w.write(sb.toString());
        w.newLine();
    }

    private static void write500Record(BufferedWriter w) throws IOException {

        w.write("500,O,S01009," + CREATED_DT + ",");
        w.newLine();
    }

    private static void writeFooter(BufferedWriter w) throws IOException {

        w.write("900");
        w.newLine();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Generates a 10-character NMI like NEM1200000, NEM1200001, etc.
     */
    private static String generateNmi(int index) {

        return String.format("NEM%07d", index);
    }

    /**
     * Returns a random consumption value between 0.000 and 2.000 (3 decimal places).
     */
    private static BigDecimal randomConsumption(Random random) {

        return BigDecimal.valueOf(random.nextDouble() * 2.0)
                         .setScale(3, RoundingMode.HALF_UP);
    }
}

