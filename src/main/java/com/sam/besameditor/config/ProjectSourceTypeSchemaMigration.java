package com.sam.besameditor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

@Component
public class ProjectSourceTypeSchemaMigration {

    private static final Logger log = LoggerFactory.getLogger(ProjectSourceTypeSchemaMigration.class);
    private static final String PROJECTS_TABLE = "projects";
    private static final String SOURCE_TYPE_COLUMN = "source_type";
    private static final String REQUIRED_ENUM_VALUE = "'LOCAL_FOLDER'";
    private static final String COLUMN_TYPE_QUERY = """
            SELECT COLUMN_TYPE
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = ?
              AND COLUMN_NAME = ?
            """;
    private static final String ALTER_ENUM_SQL = """
            ALTER TABLE projects
            MODIFY COLUMN source_type ENUM('GITHUB','LOCAL_FOLDER') NOT NULL
            """;

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public ProjectSourceTypeSchemaMigration(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateProjectSourceTypeColumn() {
        if (!isMySqlDatabase()) {
            return;
        }

        try {
            String columnType = jdbcTemplate.queryForObject(
                    COLUMN_TYPE_QUERY,
                    String.class,
                    PROJECTS_TABLE,
                    SOURCE_TYPE_COLUMN
            );
            if (columnType == null || columnType.isBlank()) {
                return;
            }

            String normalizedColumnType = columnType.toUpperCase(Locale.ROOT);
            if (!normalizedColumnType.startsWith("ENUM(")) {
                return;
            }
            if (normalizedColumnType.contains(REQUIRED_ENUM_VALUE)) {
                return;
            }

            jdbcTemplate.execute(ALTER_ENUM_SQL);
            log.info("Migrated projects.source_type enum to include LOCAL_FOLDER");
        } catch (DataAccessException ex) {
            log.warn("Failed to auto-migrate projects.source_type enum", ex);
        }
    }

    private boolean isMySqlDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            String databaseProductName = connection.getMetaData().getDatabaseProductName();
            return databaseProductName != null
                    && databaseProductName.toLowerCase(Locale.ROOT).contains("mysql");
        } catch (SQLException ex) {
            log.warn("Unable to detect database type for source_type migration", ex);
            return false;
        }
    }
}
