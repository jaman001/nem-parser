package com.flo.service;

import com.flo.model.MeterReading;
import com.flo.model.ParseResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MeterReadingService {

    private static final Logger log = LoggerFactory.getLogger(MeterReadingService.class);
    private static final int MAX_CONCURRENT_BLOCKS = 20;

    @Autowired
    private InputStreamResolver inputStreamResolver;
    @Autowired
    private Nem12Parser parser;
    @Autowired
    private NmiIngestService nmiIngestService;

    // -------------------------------------------------------------------------
    //   /sql — Virtual thread per NmiBlock.
    //   Each NmiBlock generates its SQL lines concurrently on a virtual thread.
    //   Results collected into a ConcurrentLinkedQueue (thread-safe, no locking).
    //   Intended for smaller files — returns all statements as a list in the response.
    // -------------------------------------------------------------------------
    public List<String> generateInsertStatements(MultipartFile file) throws IOException {

        ConcurrentLinkedQueue<String> statements = new ConcurrentLinkedQueue<>();

        try (InputStream in = inputStreamResolver.resolve(file); ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            Semaphore semaphore = new Semaphore(MAX_CONCURRENT_BLOCKS);
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            parser.parse(in, block -> {
                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread()
                          .interrupt();
                    throw new RuntimeException("Interrupted while waiting for semaphore", e);
                }
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        log.info("Generating SQL for nmi={}, readings={}",
                                 block.nmi(),
                                 block.readings()
                                      .size());
                        block.readings()
                             .stream()
                             .map(this::toSql)
                             .forEach(statements::add);
                        log.info("SQL generation complete for nmi={}", block.nmi());
                    } finally {
                        semaphore.release();
                    }
                }, executor);
                futures.add(future);
            });

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                             .join();
        }

        return new ArrayList<>(statements);
    }

    // -------------------------------------------------------------------------
    // /ingest — Virtual thread per NmiBlock, @Transactional per NMI.
    //   Parser buffers all 300 records per NMI then emits one NmiBlock.
    //   Each NmiBlock is dispatched to its own virtual thread.
    //   NmiIngestService#insertNmiBlock is @Transactional — all readings for
    //   one NMI (e.g. 30 days × 48 intervals = 1,440 rows) are inserted and
    //   committed together. If any insert fails, the entire NMI rolls back.
    //   All NMIs run concurrently via virtual threads.
    // -------------------------------------------------------------------------
    public ParseResult insertIntoDatabase(MultipartFile file) throws IOException {

        AtomicLong totalProcessed = new AtomicLong(0);
        AtomicLong totalFailed = new AtomicLong(0);

        try (InputStream in = inputStreamResolver.resolve(file); ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            Semaphore semaphore = new Semaphore(MAX_CONCURRENT_BLOCKS);
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            parser.parse(in, block -> {
                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread()
                          .interrupt();
                    throw new RuntimeException("Interrupted while waiting for semaphore", e);
                }
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        log.info("Inserting nmi={}, readings={}",
                                 block.nmi(),
                                 block.readings()
                                      .size());
                        nmiIngestService.insertNmiBlock(block);
                        totalProcessed.addAndGet(block.readings()
                                                      .size());
                    } catch (Exception e) {
                        log.error("Insert failed and rolled back for nmi={}: {}", block.nmi(), e.getMessage());
                        totalFailed.addAndGet(block.readings()
                                                   .size());
                    } finally {
                        semaphore.release();
                    }
                }, executor);
                futures.add(future);
            });

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                             .join();
        }

        log.info("Ingest complete. processedRows={}, failedRows={}", totalProcessed.get(), totalFailed.get());
        return new ParseResult(totalProcessed.get(), totalFailed.get());
    }

    private String toSql(MeterReading reading) {

        String nmi = reading.nmi()
                            .replace("'", "''");
        return String.format("INSERT INTO meter_readings(nmi, timestamp, consumption) VALUES ('%s', '%s', %s);",
                             nmi,
                             reading.timestamp(),
                             reading.consumption());
    }
}
