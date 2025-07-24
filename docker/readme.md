#!/bin/bash

# Start the services
echo "Starting MS SQL Server and Kafka..."
docker-compose up -d

# Wait for SQL Server to be ready
echo "Waiting for SQL Server to be ready..."
sleep 30

# Method 1: Run init script manually
echo "Running database initialization manually..."
docker exec -i mssql-dev /opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P 'YourStrong!Passw0rd' < init-scripts/01-create-database.sql

# Method 2: Copy and execute script inside container
# docker cp init-scripts/01-create-database.sql mssql-dev:/tmp/init.sql
# docker exec -it mssql-dev /opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P 'YourStrong!Passw0rd' -i /tmp/init.sql

# Check if SQL Server is running
docker-compose ps

# View logs to ensure everything started correctly
echo "Checking SQL Server logs..."
docker-compose logs sqlserver

# Test connection (optional)
echo "Testing connection..."
docker exec -it mssql-dev /opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P 'YourStrong!Passw0rd' -Q "SELECT name FROM sys.databases;"

# Stop services when done
# docker-compose down

# Stop and remove volumes (careful - this deletes all data)
# docker-compose down -v



# Manual commands you can run one by one in PowerShell

# 1. Start the containers
docker-compose up -d

# 2. Wait for SQL Server to be ready (check logs)
docker-compose logs sqlserver

# 3. Copy the SQL file to the container
docker cp init-scripts/01-create-database.sql mssql-dev:/tmp/init.sql

# 4. Execute the SQL script (Note: Using /opt/mssql-tools18/bin/sqlcmd and -C flag)
docker exec -it mssql-dev /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'YourStrong!Passw0rd' -C -i /tmp/init.sql

# 5. Verify the database was created
docker exec -it mssql-dev /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'YourStrong!Passw0rd' -C -Q "SELECT name FROM sys.databases WHERE name = 'CamelDemo';"

# 6. Check if the table exists
docker exec -it mssql-dev /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'YourStrong!Passw0rd' -C -Q "USE CamelDemo; SELECT name FROM sys.tables WHERE name = 'Order';"

# 7. Check sample data
docker exec -it mssql-dev /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'YourStrong!Passw0rd' -C -Q "USE CamelDemo; SELECT * FROM [Order];"

# Alternative: Use Get-Content to read file and pipe to docker exec
# Get-Content init-scripts/01-create-database.sql | docker exec -i mssql-dev /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'YourStrong!Passw0rd' -C

# Troubleshooting: Check what tools are available in the container
# docker exec -it mssql-dev find /opt -name "sqlcmd" -type f 2>/dev/null