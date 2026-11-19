CREATE TABLE notes (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 course_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 title VARCHAR(100) NOT NULL DEFAULT '',
 body TEXT NOT NULL,
 source_material_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_slide_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_material_name VARCHAR(200) NOT NULL,
 source_page_no INT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(owner_id) REFERENCES app_users(id),
 FOREIGN KEY(course_id) REFERENCES courses(id),
 CHECK(source_page_no > 0),
 INDEX ix_notes_course(course_id, owner_id, updated_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
