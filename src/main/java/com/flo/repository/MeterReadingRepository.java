package com.flo.repository;

import com.flo.model.MeterReading;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

@Repository
public class MeterReadingRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String sql = """
        INSERT INTO meter_readings(nmi, timestamp, consumption)
        VALUES (?, ?, ?)
        ON CONFLICT (nmi, timestamp) DO UPDATE SET consumption = EXCLUDED.consumption
        """;

    public void batchInsert(List<MeterReading> readings) {

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {

            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {

                MeterReading r = readings.get(i);
                ps.setString(1, r.nmi());
                ps.setObject(2, r.timestamp());
                ps.setBigDecimal(3, r.consumption());
            }

            @Override
            public int getBatchSize() {

                return readings.size();
            }
        });
    }
}

