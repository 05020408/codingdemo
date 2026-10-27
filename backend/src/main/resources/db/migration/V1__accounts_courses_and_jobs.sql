CREATE TABLE app_users (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  username VARCHAR(32) NOT NULL,
  username_norm VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(50) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  row_version BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT ck_user_status CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE courses (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name VARCHAR(100) NOT NULL,
  remark VARCHAR(1000) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  lifecycle_version BIGINT NOT NULL DEFAULT 1,
  row_version BIGINT NOT NULL DEFAULT 1,
  deleted_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_course_owner FOREIGN KEY (owner_id) REFERENCES app_users(id),
  CONSTRAINT ck_course_status CHECK (status IN ('ACTIVE','DELETING','DELETED')),
  INDEX ix_course_owner (owner_id,status,updated_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE async_jobs (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  course_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  type VARCHAR(32) NOT NULL,
  target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  dedup_key VARCHAR(191) NOT NULL UNIQUE,
  input_snapshot JSON NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
  attempt_count INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  run_after DATETIME(3) NOT NULL,
  lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lease_until DATETIME(3) NULL,
  heartbeat_at DATETIME(3) NULL,
  progress_done INT NULL,
  progress_total INT NULL,
  result_summary JSON NULL,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(500) NULL,
  finished_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_job_owner FOREIGN KEY (owner_id) REFERENCES app_users(id),
  CONSTRAINT fk_job_course FOREIGN KEY (course_id) REFERENCES courses(id),
  CONSTRAINT ck_job_status CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
  INDEX ix_job_queue (status,run_after,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE job_attempts (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  attempt_no INT NOT NULL,
  lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  started_at DATETIME(3) NOT NULL,
  finished_at DATETIME(3) NULL,
  outcome VARCHAR(24) NOT NULL,
  error_code VARCHAR(64) NULL,
  safe_log_summary VARCHAR(1000) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_attempt_job FOREIGN KEY (job_id) REFERENCES async_jobs(id),
  UNIQUE KEY uq_job_attempt (job_id,attempt_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE idempotency_requests (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  operation VARCHAR(64) NOT NULL,
  resource_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL,
  response_status INT NULL,
  response_body JSON NULL,
  resource_ids JSON NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_idempotency_owner FOREIGN KEY (owner_id) REFERENCES app_users(id),
  UNIQUE KEY uq_idempotency (owner_id,operation,resource_id,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
