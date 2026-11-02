-- Explicit local/demo seed. Apply after Flyway migrations, never automatically at startup.
-- Stable IDs and ON CONFLICT DO NOTHING preserve existing appointments and reference data.
BEGIN;

INSERT INTO customers (id, display_name) VALUES
('10000000-0000-4000-8000-000000000001', 'Alex Morgan'),
('10000000-0000-4000-8000-000000000002', 'Jordan Lee')
ON CONFLICT DO NOTHING;

INSERT INTO vehicles (id, customer_id, vin, make, model) VALUES
('20000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001','DEMO0000000000001','Toyota','Corolla'),
('20000000-0000-4000-8000-000000000002','10000000-0000-4000-8000-000000000002','DEMO0000000000002','Ford','Focus'),
('20000000-0000-4000-8000-000000000003','10000000-0000-4000-8000-000000000001','DEMO0000000000003','Honda','Civic')
ON CONFLICT DO NOTHING;

INSERT INTO dealerships (id, name, time_zone) VALUES
('30000000-0000-4000-8000-000000000001','Central Service Centre','Asia/Ho_Chi_Minh'),
('30000000-0000-4000-8000-000000000002','Riverside Service Centre','Asia/Ho_Chi_Minh')
ON CONFLICT DO NOTHING;

INSERT INTO service_types (id, name, duration_minutes) VALUES
('40000000-0000-4000-8000-000000000001','Oil Change',30),
('40000000-0000-4000-8000-000000000002','Vehicle Inspection',60),
('40000000-0000-4000-8000-000000000003','Electrical Diagnostics',90)
ON CONFLICT DO NOTHING;

INSERT INTO dealership_services (dealership_id, service_type_id) VALUES
('30000000-0000-4000-8000-000000000001','40000000-0000-4000-8000-000000000001'),
('30000000-0000-4000-8000-000000000001','40000000-0000-4000-8000-000000000002'),
('30000000-0000-4000-8000-000000000001','40000000-0000-4000-8000-000000000003'),
('30000000-0000-4000-8000-000000000002','40000000-0000-4000-8000-000000000001'),
('30000000-0000-4000-8000-000000000002','40000000-0000-4000-8000-000000000002')
ON CONFLICT DO NOTHING;

INSERT INTO service_bays (id, dealership_id, name) VALUES
('50000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001','Central Bay 1'),
('50000000-0000-4000-8000-000000000002','30000000-0000-4000-8000-000000000001','Central Bay 2'),
('50000000-0000-4000-8000-000000000003','30000000-0000-4000-8000-000000000002','Riverside Bay 1')
ON CONFLICT DO NOTHING;

INSERT INTO technicians (id, dealership_id, display_name) VALUES
('60000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001','Casey General'),
('60000000-0000-4000-8000-000000000002','30000000-0000-4000-8000-000000000001','Taylor Electrical'),
('60000000-0000-4000-8000-000000000003','30000000-0000-4000-8000-000000000002','Sam General')
ON CONFLICT DO NOTHING;

INSERT INTO technician_qualifications (technician_id, service_type_id) VALUES
('60000000-0000-4000-8000-000000000001','40000000-0000-4000-8000-000000000001'),
('60000000-0000-4000-8000-000000000001','40000000-0000-4000-8000-000000000002'),
('60000000-0000-4000-8000-000000000002','40000000-0000-4000-8000-000000000002'),
('60000000-0000-4000-8000-000000000002','40000000-0000-4000-8000-000000000003'),
('60000000-0000-4000-8000-000000000003','40000000-0000-4000-8000-000000000001'),
('60000000-0000-4000-8000-000000000003','40000000-0000-4000-8000-000000000002')
ON CONFLICT DO NOTHING;

-- Cover today and the following 44 local dates; Sundays are deliberately closed.
-- Separate morning/afternoon intervals demonstrate a lunch break without a recurrence engine.
WITH local_dates AS (
    SELECT d.id, d.time_zone,
           (CURRENT_TIMESTAMP AT TIME ZONE d.time_zone)::date + n AS service_date
    FROM dealerships d CROSS JOIN generate_series(0,44) AS n
    WHERE d.id IN ('30000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000002')
), windows AS (
    SELECT id, service_date,
           (service_date + start_time) AT TIME ZONE time_zone AS starts_at,
           (service_date + end_time) AT TIME ZONE time_zone AS ends_at
    FROM local_dates CROSS JOIN (VALUES (time '08:00',time '12:00'),(time '13:00',time '17:00')) AS periods(start_time,end_time)
    WHERE extract(isodow FROM service_date) <> 7
)
INSERT INTO dealership_opening_windows (id, dealership_id, starts_at, ends_at)
SELECT md5('demo-opening:' || id || ':' || extract(epoch FROM starts_at)::text)::uuid,
       id, starts_at, ends_at FROM windows
ON CONFLICT DO NOTHING;

-- Each demo technician works both daily windows, retaining the lunch break.
INSERT INTO technician_shifts (id, technician_id, starts_at, ends_at)
SELECT md5('demo-shift:' || t.id || ':' || w.id)::uuid, t.id, w.starts_at, w.ends_at
FROM technicians t JOIN dealership_opening_windows w ON w.dealership_id = t.dealership_id
WHERE t.id IN ('60000000-0000-4000-8000-000000000001','60000000-0000-4000-8000-000000000002','60000000-0000-4000-8000-000000000003')
  AND w.id = md5('demo-opening:' || w.dealership_id || ':' || extract(epoch FROM w.starts_at)::text)::uuid
ON CONFLICT DO NOTHING;

COMMIT;
