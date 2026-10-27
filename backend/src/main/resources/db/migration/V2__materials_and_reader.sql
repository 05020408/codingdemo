CREATE TABLE materials (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 course_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 display_name VARCHAR(200) NOT NULL,
 size_bytes BIGINT NOT NULL,
 sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 content_version BIGINT NOT NULL DEFAULT 1,
 lifecycle_version BIGINT NOT NULL DEFAULT 1,
 row_version BIGINT NOT NULL DEFAULT 1,
 slide_count INT NOT NULL,
 mapping_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 original_asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 pdf_asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 deleted_at DATETIME(3) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(course_id) REFERENCES courses(id),
 CHECK(status IN ('ACTIVE','DELETING','DELETED')),
 INDEX ix_material_course(course_id,status,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE file_assets (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 material_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind VARCHAR(16) NOT NULL,
 storage_key VARCHAR(500) NOT NULL UNIQUE,
 media_type VARCHAR(100) NOT NULL,
 size_bytes BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'READY',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(material_id) REFERENCES materials(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE slides (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 material_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 page_no INT NOT NULL,
 source_slide_id BIGINT NOT NULL,
 is_hidden BOOLEAN NOT NULL,
 title TEXT NULL,
 text_content MEDIUMTEXT NULL,
 text_revision BIGINT NULL,
 preview_asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 thumbnail_asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(material_id) REFERENCES materials(id),
 UNIQUE KEY uq_slide_page(material_id,page_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE material_stages (
 material_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 stage VARCHAR(24) NOT NULL,
 status VARCHAR(24) NOT NULL,
 requested_revision BIGINT NOT NULL DEFAULT 1,
 published_revision BIGINT NULL,
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 error_code VARCHAR(64) NULL,
 error_message VARCHAR(500) NULL,
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(material_id,stage),
 FOREIGN KEY(material_id) REFERENCES materials(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE reading_progress (
 owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 material_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 last_page_no INT NOT NULL,
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(owner_id,material_id),
 FOREIGN KEY(owner_id) REFERENCES app_users(id),
 FOREIGN KEY(material_id) REFERENCES materials(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
