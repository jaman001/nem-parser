package com.flo.service;

import com.flo.model.MeterReading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.flo.model.Nem12RecordType.HEADER;
import static com.flo.model.Nem12RecordType.INTERVAL_DATA;
import static com.flo.model.Nem12RecordType.NMI_DATA_DETAILS;

@Component
public class Nem12Parser {

    private static final Logger log = LoggerFactory.getLogger(Nem12Parser.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * Parses the NEM12 stream and calls the consumer once per NMI block (200 record).
     * All 300 records under a 200 record are buffered and emitted together as one NmiBlock.
     * IMPORTANT: the Consumer pattern is intentional here — each NmiBlock is emitted and
     * handed off to the caller immediately when the next 200 or EOF is reached.
     * The caller (MeterReadingService) dispatches each block to a virtual thread and moves on.
     * This means only MAX_CONCURRENT_BLOCKS are ever held in memory at once, regardless
     * of how large the file is. Returning List<NmiBlock> would accumulate the entire file
     * in memory before any processing begins, causing OutOfMemoryError on large files.
     */
    void parse(InputStream inputStream, Consumer<NmiBlock> consumer) throws IOException {

        String currentNmi = null;
        int intervalMinutes = 30;
        List<MeterReading> currentBlock = new ArrayList<>();
        long totalReadings = 0;
        int totalBlocks = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                String[] cols = splitCsvLine(line);
                if (cols.length == 0) {
                    continue;
                }

                String recordType = cols[0].trim();

                if (HEADER.matches(recordType)) {
                    if (cols.length < 5) {
                        throw new IllegalArgumentException("Invalid 100 record: insufficient columns");
                    }
                    log.info("Parsing NEM12 file: fromMDP={}, toMDP={}", cols[3].trim(), cols[4].trim());
                    continue;
                }

                if (NMI_DATA_DETAILS.matches(recordType)) {
                    if (cols.length < 9) {
                        throw new IllegalArgumentException("Invalid 200 record: insufficient columns");
                    }

                    // Flush previous NMI block before starting a new one
                    if (currentNmi != null && !currentBlock.isEmpty()) {
                        consumer.accept(new NmiBlock(currentNmi, List.copyOf(currentBlock)));
                        totalReadings += currentBlock.size();
                        totalBlocks++;
                        currentBlock.clear();
                    }

                    currentNmi = cols[1].trim();
                    if (currentNmi.isEmpty()) {
                        throw new IllegalArgumentException("Invalid 200 record: missing NMI");
                    }
                    intervalMinutes = parsePositiveInt(cols[8], "Invalid 200 record: interval length must be a positive integer");
                    if ((24 * 60) % intervalMinutes != 0) {
                        throw new IllegalArgumentException("Invalid 200 record: interval length must divide 1440 minutes");
                    }
                    log.info("NMI block started: nmi={}, intervalMinutes={}", currentNmi, intervalMinutes);
                    continue;
                }

                if (!INTERVAL_DATA.matches(recordType)) {
                    continue;
                }

                if (currentNmi == null) {
                    throw new IllegalArgumentException("Invalid 300 record: encountered before any 200 record");
                }
                if (cols.length < 3) {
                    throw new IllegalArgumentException("Invalid 300 record: insufficient columns");
                }

                LocalDate date;
                try {
                    date = LocalDate.parse(cols[1].trim(), DATE_FORMAT);
                } catch (Exception ex) {
                    throw new IllegalArgumentException("Invalid 300 record date: " + cols[1].trim(), ex);
                }

                int expectedIntervals = (24 * 60) / intervalMinutes;
                if (cols.length < 2 + expectedIntervals) {
                    throw new IllegalArgumentException("Invalid 300 record: expected " + expectedIntervals + " interval values for nmi=" + currentNmi);
                }

                for (int intervalNumber = 1; intervalNumber <= expectedIntervals; intervalNumber++) {
                    BigDecimal consumption = getConsumption(cols, intervalNumber, currentNmi);
                    LocalDateTime ts = LocalDateTime.of(date, LocalTime.MIDNIGHT)
                                                    .plusMinutes((long) intervalMinutes * intervalNumber);
                    currentBlock.add(new MeterReading(currentNmi, ts, consumption));
                }
            }

            // Flush the last NMI block at EOF
            if (currentNmi != null && !currentBlock.isEmpty()) {
                consumer.accept(new NmiBlock(currentNmi, List.copyOf(currentBlock)));
                totalReadings += currentBlock.size();
                totalBlocks++;
            }
        }

        log.info("Parsing complete. totalBlocks={}, totalReadings={}", totalBlocks, totalReadings);
    }

    private static BigDecimal getConsumption(String[] cols, int intervalNumber, String nmi) {

        String raw = cols[1 + intervalNumber].trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("Invalid 300 record: blank consumption value at interval " + intervalNumber + " for nmi=" + nmi);
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid 300 record: non-numeric consumption value at interval "
                                               + intervalNumber
                                               + ": "
                                               + raw
                                               + " for nmi="
                                               + nmi, ex);
        }
    }

    private int parsePositiveInt(String text, String errorMessage) {

        try {
            int value = Integer.parseInt(text.trim());
            if (value <= 0) {
                throw new IllegalArgumentException(errorMessage);
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(errorMessage, ex);
        }
    }

    private String[] splitCsvLine(String line) {

        return line.split(",", -1);
    }

    /**
     * All readings for one NMI — all 300 records under a single 200 record buffered together.
     * e.g. 30 days × 48 intervals = 1,440 readings inserted in one transaction.
     */
    public record NmiBlock(String nmi, List<MeterReading> readings) {

    }
}
