CREATE TABLE chat_sessions (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 course_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 material_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 title VARCHAR(100) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(course_id) REFERENCES courses(id),
 FOREIGN KEY(material_id) REFERENCES materials(id),
 INDEX ix_chat_course(course_id,updated_at),
 INDEX ix_chat_material(material_id,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE chat_turns (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sequence_no INT NOT NULL,
 question TEXT NOT NULL,
 context_snapshot JSON NOT NULL,
 history_snapshot JSON NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
 last_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 answer JSON NULL,
 error_code VARCHAR(64) NULL,
 error_message VARCHAR(500) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(session_id) REFERENCES chat_sessions(id),
 UNIQUE KEY uq_chat_sequence(session_id,sequence_no),
 CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
