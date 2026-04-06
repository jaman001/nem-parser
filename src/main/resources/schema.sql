CREATE TABLE IF NOT EXISTS meter_readings (
  id uuid DEFAULT gen_random_uuid() NOT NULL,
  nmi varchar(10) NOT NULL,
  timestamp timestamp NOT NULL,
  consumption numeric NOT NULL,
  CONSTRAINT meter_readings_pk PRIMARY KEY (id),
  CONSTRAINT meter_readings_unique_consumption UNIQUE (nmi, timestamp)
);
