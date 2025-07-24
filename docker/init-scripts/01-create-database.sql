-- Create this file as: init-scripts/01-create-database.sql
-- This will automatically run when the container starts

USE master;
GO

-- Create the database if it doesn't exist
IF NOT EXISTS (SELECT name FROM sys.databases WHERE name = 'CamelDemo')
BEGIN
    CREATE DATABASE CamelDemo;
END
GO

USE CamelDemo;
GO

-- Create the Order table
IF NOT EXISTS (SELECT * FROM sysobjects WHERE name='Order' AND xtype='U')
BEGIN
    CREATE TABLE [Order] (
        id INT PRIMARY KEY,
        name NVARCHAR(255) NOT NULL,
        description NVARCHAR(1000),
        effective_date DATE,
        status NVARCHAR(50),
        created_date DATETIME2 DEFAULT GETDATE(),
        updated_date DATETIME2 DEFAULT GETDATE()
    );
END
GO

-- Insert sample data
IF NOT EXISTS (SELECT 1 FROM [Order] WHERE id = 1)
BEGIN
    INSERT INTO [Order] (id, name, description, effective_date, status)
    VALUES (1, 'Sample Order', 'Initial test order', '2025-07-01', 'PENDING');
END
GO

-- Create an index for better performance
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'IX_Order_Status')
BEGIN
    CREATE INDEX IX_Order_Status ON [Order] (status);
END
GO

PRINT 'Database and table created successfully!';