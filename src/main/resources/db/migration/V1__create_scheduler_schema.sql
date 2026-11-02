CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE customers (
    id uuid PRIMARY KEY,
    display_name text NOT NULL
);

CREATE TABLE vehicles (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL REFERENCES customers(id),
    vin text NOT NULL UNIQUE,
    make text NOT NULL,
    model text NOT NULL,
    CONSTRAINT vehicles_id_customer_unique UNIQUE (id, customer_id)
);

CREATE TABLE dealerships (
    id uuid PRIMARY KEY,
    name text NOT NULL,
    time_zone text NOT NULL,
    active boolean NOT NULL DEFAULT true
);

CREATE TABLE service_types (
    id uuid PRIMARY KEY,
    name text NOT NULL,
    duration_minutes integer NOT NULL,
    active boolean NOT NULL DEFAULT true,
    CONSTRAINT service_types_positive_duration CHECK (duration_minutes > 0)
);

CREATE TABLE dealership_services (
    dealership_id uuid NOT NULL REFERENCES dealerships(id),
    service_type_id uuid NOT NULL REFERENCES service_types(id),
    PRIMARY KEY (dealership_id, service_type_id)
);

CREATE TABLE service_bays (
    id uuid PRIMARY KEY,
    dealership_id uuid NOT NULL REFERENCES dealerships(id),
    name text NOT NULL,
    active boolean NOT NULL DEFAULT true,
    CONSTRAINT service_bays_id_dealership_unique UNIQUE (id, dealership_id)
);

CREATE TABLE technicians (
    id uuid PRIMARY KEY,
    dealership_id uuid NOT NULL REFERENCES dealerships(id),
    display_name text NOT NULL,
    active boolean NOT NULL DEFAULT true,
    CONSTRAINT technicians_id_dealership_unique UNIQUE (id, dealership_id)
);

CREATE TABLE technician_qualifications (
    technician_id uuid NOT NULL REFERENCES technicians(id),
    service_type_id uuid NOT NULL REFERENCES service_types(id),
    PRIMARY KEY (technician_id, service_type_id)
);

CREATE TABLE dealership_opening_windows (
    id uuid PRIMARY KEY,
    dealership_id uuid NOT NULL REFERENCES dealerships(id),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    CONSTRAINT opening_windows_positive_finite_interval CHECK (
        isfinite(starts_at) AND isfinite(ends_at) AND starts_at < ends_at
    ),
    CONSTRAINT opening_windows_dealership_interval_unique UNIQUE (dealership_id, starts_at, ends_at)
);

CREATE TABLE technician_shifts (
    id uuid PRIMARY KEY,
    technician_id uuid NOT NULL REFERENCES technicians(id),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    CONSTRAINT technician_shifts_positive_finite_interval CHECK (
        isfinite(starts_at) AND isfinite(ends_at) AND starts_at < ends_at
    ),
    CONSTRAINT technician_shifts_technician_interval_unique UNIQUE (technician_id, starts_at, ends_at)
);

CREATE TABLE appointments (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL,
    vehicle_id uuid NOT NULL,
    dealership_id uuid NOT NULL,
    service_type_id uuid NOT NULL,
    technician_id uuid NOT NULL,
    service_bay_id uuid NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    duration_minutes integer NOT NULL,
    status text NOT NULL DEFAULT 'CONFIRMED',
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT appointments_vehicle_customer_fk FOREIGN KEY (vehicle_id, customer_id)
        REFERENCES vehicles(id, customer_id),
    CONSTRAINT appointments_bay_dealership_fk FOREIGN KEY (service_bay_id, dealership_id)
        REFERENCES service_bays(id, dealership_id),
    CONSTRAINT appointments_technician_dealership_fk FOREIGN KEY (technician_id, dealership_id)
        REFERENCES technicians(id, dealership_id),
    CONSTRAINT appointments_dealership_service_fk FOREIGN KEY (dealership_id, service_type_id)
        REFERENCES dealership_services(dealership_id, service_type_id),
    CONSTRAINT appointments_technician_qualification_fk FOREIGN KEY (technician_id, service_type_id)
        REFERENCES technician_qualifications(technician_id, service_type_id),
    CONSTRAINT appointments_positive_finite_interval CHECK (
        isfinite(starts_at) AND isfinite(ends_at) AND starts_at < ends_at
    ),
    CONSTRAINT appointments_duration_matches_interval CHECK (
        duration_minutes > 0 AND ends_at = starts_at + duration_minutes * interval '1 minute'
    ),
    CONSTRAINT appointments_confirmed_status CHECK (status = 'CONFIRMED'),
    CONSTRAINT appointments_finite_created_at CHECK (isfinite(created_at)),
    CONSTRAINT appointments_no_bay_overlap EXCLUDE USING gist (
        service_bay_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&
    ),
    CONSTRAINT appointments_no_technician_overlap EXCLUDE USING gist (
        technician_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&
    ),
    CONSTRAINT appointments_no_vehicle_overlap EXCLUDE USING gist (
        vehicle_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&
    )
);

CREATE INDEX service_bays_dealership_idx ON service_bays (dealership_id, id);
CREATE INDEX technicians_dealership_idx ON technicians (dealership_id, id);
CREATE INDEX qualifications_service_idx ON technician_qualifications (service_type_id, technician_id);
CREATE INDEX appointments_customer_history_idx ON appointments (customer_id, starts_at, id);
CREATE INDEX appointments_vehicle_history_idx ON appointments (vehicle_id, starts_at, id);
-- Opening-window and shift unique constraints already index their owner and start time.
-- Each exclusion constraint already provides its resource/range GiST index.
