package com.flo.api;

import com.flo.controller.MeterReadingController;
import com.flo.model.ParseResult;
import com.flo.service.MeterReadingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MeterReadingController.class)
class MeterReadingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MeterReadingService meterReadingService;

    @Test
    void shouldReturnSqlStatements() throws Exception {
        when(meterReadingService.generateInsertStatements(any())).thenReturn(List.of(
                "INSERT INTO meter_readings(nmi, timestamp, consumption) VALUES ('NEM001', '2005-03-01T00:30', 1.0);",
                "INSERT INTO meter_readings(nmi, timestamp, consumption) VALUES ('NEM001', '2005-03-01T01:00', 2.0);"
        ));

        MockMultipartFile file = new MockMultipartFile("file", "sample.csv", MediaType.TEXT_PLAIN_VALUE, "x".getBytes());

        mockMvc.perform(multipart("/api/v1/meter-readings/sql").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statements.length()").value(2))
                .andExpect(jsonPath("$.statements[0]").value(org.hamcrest.Matchers.containsString("NEM001")));
    }

    @Test
    void shouldReturnIngestResult() throws Exception {
        when(meterReadingService.insertIntoDatabase(any())).thenReturn(new ParseResult(100, 2));

        MockMultipartFile file = new MockMultipartFile("file", "sample.csv", MediaType.TEXT_PLAIN_VALUE, "x".getBytes());

        mockMvc.perform(multipart("/api/v1/meter-readings/ingest").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedRows").value(100))
                .andExpect(jsonPath("$.skippedRows").value(2));
    }
}


