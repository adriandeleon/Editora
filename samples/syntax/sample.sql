CREATE TABLE users (
    id   INTEGER PRIMARY KEY,
    name TEXT NOT NULL
);

SELECT id, name
FROM users
WHERE name LIKE 'a%'
ORDER BY name;
