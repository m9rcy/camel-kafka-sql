#!/bin/bash

# run-init.sh - Manual script to initialize the database

echo "Waiting for SQL Server to be ready..."
sleep 15

echo "Running database initialization..."

# Execute the SQL script
docker exec -i mssql-dev /opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P 'YourStrong!Passw0rd' < init-scripts/01-create-database.sql

if [ $? -eq 0 ]; then
    echo "Database initialization completed successfully!"
else
    echo "Database initialization failed!"
    exit 1
fi