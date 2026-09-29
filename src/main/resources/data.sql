-- Seeds the pharmacy's stock on first start; never overwrites quantities afterwards
-- (runs on every start via spring.sql.init.mode=always, WHEN NOT MATCHED keeps it idempotent).
MERGE INTO stock s
USING (VALUES
    ('Amoxicillin',  'Antibiotic',     40),
    ('Ibuprofen',    'Pain relief',    60),
    ('Lisinopril',   'Blood pressure', 35),
    ('Metformin',    'Diabetes',       45),
    ('Atorvastatin', 'Cholesterol',    30),
    ('Omeprazole',   'Acid reflux',    40),
    ('Cetirizine',   'Allergy',        50),
    -- Deliberately scarce, handy for demoing the out-of-stock path.
    ('Salbutamol',   'Asthma',          2)
) AS v (medication, category, quantity)
ON s.medication = v.medication
WHEN NOT MATCHED THEN
    INSERT (medication, category, quantity) VALUES (v.medication, v.category, v.quantity);
