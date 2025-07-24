# init-database.ps1 - PowerShell script to initialize SQL Server

Write-Host "Waiting for SQL Server to be ready..." -ForegroundColor Green
Start-Sleep -Seconds 30

Write-Host "Copying SQL script to container..." -ForegroundColor Green
docker cp init-scripts/01-create-database.sql mssql-dev:/tmp/init.sql

Write-Host "Running database initialization..." -ForegroundColor Green
# SQL Server 2022 uses /opt/mssql-tools18/bin/sqlcmd
docker exec -it mssql-dev /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'YourStrong!Passw0rd' -C -i /tmp/init.sql

if ($LASTEXITCODE -eq 0) {
    Write-Host "Database initialization completed successfully!" -ForegroundColor Green

    # Test the database
    Write-Host "Testing database connection..." -ForegroundColor Yellow
    docker exec -it mssql-dev /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'YourStrong!Passw0rd' -C -Q "USE CamelDemo; SELECT name FROM sys.tables WHERE name = 'Order';"

    Write-Host "Checking sample data..." -ForegroundColor Yellow
    docker exec -it mssql-dev /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'YourStrong!Passw0rd' -C -Q "USE CamelDemo; SELECT * FROM [Order];"
} else {
    Write-Host "Database initialization failed!" -ForegroundColor Red
    exit 1
}