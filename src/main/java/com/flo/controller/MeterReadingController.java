package com.flo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.flo.model.ParseResult;
import com.flo.service.MeterReadingService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/meter-readings")
@Tag(name = "Meter Readings")
public class MeterReadingController {

    @Autowired
    private MeterReadingService meterReadingService;

    @PostMapping(path = "/sql", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Generate SQL insert statements from NEM12 file (csv or zip)")
    public ResponseEntity<Map<String, List<String>>> generateSql(@RequestPart("file") MultipartFile file) throws IOException {

        return ResponseEntity.ok(Map.of("statements", meterReadingService.generateInsertStatements(file)));
    }

    @PostMapping(path = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Parse and insert NEM12 file into database (csv or zip)")
    public ResponseEntity<ParseResult> ingest(@RequestPart("file") MultipartFile file) throws IOException {

        return ResponseEntity.ok(meterReadingService.insertIntoDatabase(file));
    }
}
