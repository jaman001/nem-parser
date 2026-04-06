package com.flo.service;

import com.flo.model.MeterReading;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Nem12ParserTest {

    private static final Nem12Parser parser = new Nem12Parser();

    private static String build300Record(String date, double startValue) {
        StringBuilder sb = new StringBuilder("300,").append(date);
        for (int i = 0; i < 48; i++) {
            sb.append(',').append(String.format("%.3f", startValue + i * 0.01));
        }
        sb.append(",A,,,20050310121004,");
        return sb.toString();
    }

    @Test
    void shouldBufferAll300RecordsIntoOneNmiBlock() throws Exception {
        String content = String.join("\n",
                "100,NEM12,200506081149,UNITEDDP,NEMMCO",
                "200,NEM1201009,E1E2,1,E1,N1,01009,kWh,30,20050610",
                build300Record("20050301", 1.0),
                build300Record("20050302", 2.0),
                "900"
        );

        List<Nem12Parser.NmiBlock> blocks = new ArrayList<>();
        parser.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), blocks::add);

        assertEquals(1, blocks.size());
        assertEquals("NEM1201009", blocks.get(0).nmi());
        assertEquals(96, blocks.get(0).readings().size());
    }

    @Test
    void shouldEmitOneNmiBlockPerNmi() throws Exception {
        StringBuilder content = new StringBuilder();
        content.append("100,NEM12,200506081149,UNITEDDP,NEMMCO\n");
        for (int nmi = 0; nmi < 3; nmi++) {
            content.append(String.format("200,NEM120100%d,E1E2,1,E1,N1,01009,kWh,30,20050610\n", nmi));
            for (int day = 1; day <= 30; day++) {
                content.append(build300Record(String.format("200503%02d", day), nmi + day * 0.01)).append("\n");
            }
        }
        content.append("900");

        List<Nem12Parser.NmiBlock> blocks = new ArrayList<>();
        parser.parse(new ByteArrayInputStream(content.toString().getBytes(StandardCharsets.UTF_8)), blocks::add);

        assertEquals(3, blocks.size());
        blocks.forEach(b -> assertEquals(1_440, b.readings().size()));
        assertEquals(3 * 1_440, blocks.stream().mapToLong(b -> b.readings().size()).sum());
    }

    @Test
    void shouldProduceCorrectTimestamps() throws Exception {
        String content = String.join("\n",
                "100,NEM12,200506081149,UNITEDDP,NEMMCO",
                "200,NEM1201009,E1E2,1,E1,N1,01009,kWh,30,20050610",
                build300Record("20050301", 1.0),
                "900"
        );

        List<MeterReading> readings = new ArrayList<>();
        parser.parse(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                block -> readings.addAll(block.readings())
        );

        assertEquals(48, readings.size());
        assertEquals("2005-03-01T00:30", readings.get(0).timestamp().toString());
        assertEquals("2005-03-01T01:00", readings.get(1).timestamp().toString());
        assertEquals("2005-03-02T00:00", readings.get(47).timestamp().toString());
    }

    @Test
    void shouldThrowOn300BeforeAny200() {
        String content = String.join("\n",
                "100,NEM12,200506081149,UNITEDDP,NEMMCO",
                build300Record("20050301", 1.0),
                "900"
        );
        assertThrows(IllegalArgumentException.class, () ->
                parser.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), b -> {})
        );
    }

    @Test
    void shouldThrowOnInvalid100Record() {
        String content = "100,NEM12,200506081149\n900";
        assertThrows(IllegalArgumentException.class, () ->
                parser.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), b -> {})
        );
    }

    @Test
    void shouldThrowOnNonNumericConsumptionValue() {
        String bad300 = "300,20050301," + "INVALID,".repeat(47) + "INVALID,A,,,20050310121004,";
        String content = String.join("\n",
                "100,NEM12,200506081149,UNITEDDP,NEMMCO",
                "200,NEM1201009,E1E2,1,E1,N1,01009,kWh,30,20050610",
                bad300,
                "900"
        );
        assertThrows(IllegalArgumentException.class, () ->
                parser.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), b -> {})
        );
    }
}
