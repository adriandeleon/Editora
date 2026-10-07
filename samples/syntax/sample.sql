-- A small SQL sample for syntax highlighting.
-- Covers DDL with constraints, an index, inserts, a CTE, joins, aggregation,
-- a window function, a CASE expression, and a transaction.

CREATE TABLE shelves (
    id       INTEGER PRIMARY KEY,
    name     VARCHAR(40) NOT NULL UNIQUE,
    capacity INTEGER     NOT NULL DEFAULT 64 CHECK (capacity > 0)
);

CREATE TABLE items (
    id        INTEGER PRIMARY KEY,
    shelf_id  INTEGER NOT NULL REFERENCES shelves (id) ON DELETE CASCADE,
    name      TEXT    NOT NULL,
    weight_kg NUMERIC(6, 2),
    added_on  DATE    NOT NULL DEFAULT CURRENT_DATE
);

CREATE INDEX idx_items_shelf ON items (shelf_id, name);

/* A block comment: seed data, including an escaped quote. */
INSERT INTO shelves (id, name) VALUES (1, 'workshop'), (2, 'library');
INSERT INTO items (id, shelf_id, name, weight_kg) VALUES
    (1, 1, 'hammer', 0.60),
    (2, 1, 'anvil', 45.00),
    (3, 2, 'The Hitchhiker''s Guide', NULL);

WITH weighed AS (
    SELECT shelf_id, name, COALESCE(weight_kg, 0) AS kg
    FROM items
)
SELECT s.name                                           AS shelf,
       w.name                                           AS item,
       CASE WHEN w.kg > 10 THEN 'heavy' ELSE 'light' END AS class,
       RANK() OVER (PARTITION BY s.id ORDER BY w.kg DESC) AS rank_on_shelf
FROM shelves AS s
LEFT JOIN weighed AS w ON w.shelf_id = s.id
WHERE s.name LIKE 'w%' OR w.kg BETWEEN 0 AND 1
ORDER BY shelf, rank_on_shelf;

SELECT s.name, COUNT(i.id) AS item_count, ROUND(AVG(i.weight_kg), 1) AS avg_kg
FROM shelves s
JOIN items i ON i.shelf_id = s.id
GROUP BY s.name
HAVING COUNT(i.id) >= 1;

BEGIN;
UPDATE items SET weight_kg = weight_kg * 1.1 WHERE shelf_id = 1 AND weight_kg IS NOT NULL;
DELETE FROM items WHERE name IN ('saw', 'drill');
COMMIT;
