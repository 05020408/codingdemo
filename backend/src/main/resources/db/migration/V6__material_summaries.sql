CREATE TABLE material_summaries (
 material_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 current_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 row_version BIGINT NOT NULL DEFAULT 1,
 FOREIGN KEY(material_id) REFERENCES materials(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE material_summary_versions (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 material_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 version_no INT NOT NULL,
 origin VARCHAR(16) NOT NULL,
 status VARCHAR(24) NOT NULL,
 base_head_version BIGINT NOT NULL,
 source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_snapshot JSON NOT NULL,
 total_pages INT NOT NULL,
 empty_pages INT NOT NULL,
 markdown MEDIUMTEXT NULL,
 model VARCHAR(100) NULL,
 error_code VARCHAR(64) NULL,
 error_message VARCHAR(500) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(material_id) REFERENCES materials(id),
 UNIQUE KEY uq_summary_version(material_id,version_no),
 CHECK(origin IN ('AI','USER')),
 CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE material_summary_parts (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 position_no INT NOT NULL,
 kind VARCHAR(16) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'WAITING',
 input_snapshot JSON NOT NULL,
 result JSON NULL,
 last_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 FOREIGN KEY(version_id) REFERENCES material_summary_versions(id),
 UNIQUE KEY uq_summary_part(version_id,position_no),
 CHECK(kind IN ('PART','FINAL')),
 CHECK(status IN ('WAITING','QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE chat_turns ADD COLUMN summary_snapshot JSON NULL;
