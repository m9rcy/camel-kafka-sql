CREATE TABLE [Orders] (
    id INT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    effective_date DATE,
    status VARCHAR(50)
);
