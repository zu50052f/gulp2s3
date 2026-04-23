package com.example.blobstream.repository;

import com.example.blobstream.model.StoredFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class StoredFileRepository {

    private static final RowMapper<StoredFile> ROW_MAPPER = new StoredFileRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public StoredFileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public StoredFile insert(String fileName, String contentType, String objectKey, long contentLength, long lobOid) {
        Long id = jdbcTemplate.queryForObject(
                """
                        insert into stored_file (file_name, content_type, object_key, content_length, lob_oid)
                        values (?, ?, ?, ?, ?)
                        returning id
                        """,
                Long.class,
                fileName,
                contentType,
                objectKey,
                contentLength,
                lobOid
        );

        if (id == null) {
            throw new IllegalStateException("Database did not return an id for the stored file.");
        }

        return findById(id)
                .orElseThrow(() -> new IllegalStateException("Database inserted a file row that could not be reloaded."));
    }

    public Optional<StoredFile> findById(long id) {
        List<StoredFile> storedFiles = jdbcTemplate.query(
                """
                        select id, file_name, content_type, object_key, content_length, lob_oid, created_at
                        from stored_file
                        where id = ?
                        """,
                ROW_MAPPER,
                id
        );
        return storedFiles.stream().findFirst();
    }

    public Optional<StoredFile> findByObjectKey(String objectKey) {
        List<StoredFile> storedFiles = jdbcTemplate.query(
                """
                        select id, file_name, content_type, object_key, content_length, lob_oid, created_at
                        from stored_file
                        where object_key = ?
                        """,
                ROW_MAPPER,
                objectKey
        );
        return storedFiles.stream().findFirst();
    }

    public List<StoredFile> findAll() {
        return jdbcTemplate.query(
                """
                        select id, file_name, content_type, object_key, content_length, lob_oid, created_at
                        from stored_file
                        order by id desc
                        """,
                ROW_MAPPER
        );
    }

    private static final class StoredFileRowMapper implements RowMapper<StoredFile> {
        @Override
        public StoredFile mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new StoredFile(
                    rs.getLong("id"),
                    rs.getString("file_name"),
                    rs.getString("content_type"),
                    rs.getString("object_key"),
                    rs.getLong("content_length"),
                    rs.getLong("lob_oid"),
                    rs.getObject("created_at", OffsetDateTime.class)
            );
        }
    }
}
