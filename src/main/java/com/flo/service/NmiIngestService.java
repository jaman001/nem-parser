package com.flo.service;

import com.flo.model.MeterReading;
import com.flo.repository.MeterReadingRepository;
import com.flo.service.Nem12Parser.NmiBlock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles transactional insert for a single NmiBlock.
 *
 * Separate Spring bean intentionally — Spring @Transactional works via AOP proxy.
 * Self-invocation from the same bean bypasses the proxy and loses transaction semantics.
 * By delegating here, each NMI block gets its own real transaction that commits or
 * rolls back independently without affecting any other NMI.
 *
 * Example: NEM1201010 has 30 300-records × 48 intervals = 1,440 readings.
 * All 1,440 inserts go in one transaction. If any fails, all 1,440 roll back.
 */
@Service
public class NmiIngestService {

    private static final Logger log = LoggerFactory.getLogger(NmiIngestService.class);
    private static final int BATCH_SIZE = 1_000;

    private final MeterReadingRepository repository;

    public NmiIngestService(MeterReadingRepository repository) {
        this.repository = repository;
    }

    /**
     * Inserts all readings for one NMI block in a single transaction.
     * All 300-record readings under a 200 record are committed together.
     * If any insert fails, the entire NMI block rolls back.
     */
    @Transactional
    public void insertNmiBlock(NmiBlock block) {
        log.info("Transaction opened for nmi={}, totalReadings={}", block.nmi(), block.readings().size());

        List<MeterReading> buffer = new ArrayList<>(BATCH_SIZE);
        for (MeterReading reading : block.readings()) {
            buffer.add(reading);
            if (buffer.size() >= BATCH_SIZE) {
                repository.batchInsert(buffer);
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) {
            repository.batchInsert(buffer);
        }

        log.info("Transaction committed for nmi={}, totalReadings={}", block.nmi(), block.readings().size());
    }
}
