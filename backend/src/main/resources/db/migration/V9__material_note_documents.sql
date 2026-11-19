CREATE TABLE material_notes (
 material_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 course_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 material_name VARCHAR(200) NOT NULL,
 markdown LONGTEXT NOT NULL,
 row_version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(course_id) REFERENCES courses(id),
 FOREIGN KEY(owner_id) REFERENCES app_users(id),
 INDEX ix_material_notes_course(course_id, owner_id, updated_at, material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
