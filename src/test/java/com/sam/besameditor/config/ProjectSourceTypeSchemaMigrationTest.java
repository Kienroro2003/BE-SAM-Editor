package com.sam.besameditor.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectSourceTypeSchemaMigrationTest {

    @Mock
    private DataSource dataSource;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private Connection connection;
    @Mock
    private DatabaseMetaData databaseMetaData;

    private ProjectSourceTypeSchemaMigration migration;

    @BeforeEach
    void setUp() {
        migration = new ProjectSourceTypeSchemaMigration(dataSource, jdbcTemplate);
    }

    @Test
    void migrateProjectSourceTypeColumn_ShouldAlterEnum_WhenMySqlAndLocalFolderMissing() throws SQLException {
        mockDatabase("MySQL");
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq("projects"), eq("source_type")))
                .thenReturn("enum('GITHUB')");

        migration.migrateProjectSourceTypeColumn();

        verify(jdbcTemplate).execute(contains("ALTER TABLE projects"));
    }

    @Test
    void migrateProjectSourceTypeColumn_ShouldSkip_WhenEnumAlreadyContainsLocalFolder() throws SQLException {
        mockDatabase("MySQL");
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq("projects"), eq("source_type")))
                .thenReturn("enum('GITHUB','LOCAL_FOLDER')");

        migration.migrateProjectSourceTypeColumn();

        verify(jdbcTemplate, never()).execute(any(String.class));
    }

    @Test
    void migrateProjectSourceTypeColumn_ShouldSkip_WhenNotMySql() throws SQLException {
        mockDatabase("H2");

        migration.migrateProjectSourceTypeColumn();

        verify(jdbcTemplate, never()).queryForObject(any(String.class), eq(String.class), any(), any());
        verify(jdbcTemplate, never()).execute(any(String.class));
    }

    private void mockDatabase(String productName) throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(databaseMetaData);
        when(databaseMetaData.getDatabaseProductName()).thenReturn(productName);
    }
}
