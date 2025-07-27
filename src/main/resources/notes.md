
-- SQL Server MERGE statement for upsert operation
-- This can be used in your Camel route for more efficient upserts

MERGE [Order] AS target
USING (SELECT :id as id, :name as name, :description as description,
:effectiveDate as effective_date, :status as status) AS source
ON target.id = source.id
WHEN MATCHED AND (
target.name != source.name OR
target.description != source.description OR
target.effective_date != source.effective_date OR
target.status != source.status
) THEN
UPDATE SET
name = source.name,
description = source.description,
effective_date = source.effective_date,
status = source.status
WHEN NOT MATCHED THEN
INSERT (id, name, description, effective_date, status)
VALUES (source.id, source.name, source.description, source.effective_date, source.status);