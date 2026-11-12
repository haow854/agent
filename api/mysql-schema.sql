CREATE DATABASE IF NOT EXISTS `docagent`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `docagent`;

CREATE TABLE IF NOT EXISTS `user` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `username` VARCHAR(50) NOT NULL,
  `password` VARCHAR(100) NOT NULL,
  `email` VARCHAR(100) NOT NULL,
  `phone` VARCHAR(20) NOT NULL,
  `role` VARCHAR(20) NOT NULL DEFAULT 'USER',
  `status` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`),
  UNIQUE KEY `uk_user_email` (`email`),
  UNIQUE KEY `uk_user_phone` (`phone`),
  KEY `idx_user_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `file_metadata` (
  `id` VARCHAR(64) NOT NULL,
  `file_id` VARCHAR(64) NOT NULL,
  `file_name` VARCHAR(255) NOT NULL,
  `original_file_name` VARCHAR(255) DEFAULT NULL,
  `file_path` VARCHAR(1024) DEFAULT NULL,
  `file_type` VARCHAR(128) DEFAULT NULL,
  `file_size` BIGINT NOT NULL DEFAULT 0,
  `md5` VARCHAR(64) DEFAULT NULL,
  `storage_type` VARCHAR(32) DEFAULT NULL,
  `bucket_name` VARCHAR(128) DEFAULT NULL,
  `object_key` VARCHAR(512) DEFAULT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `create_by` VARCHAR(64) DEFAULT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_metadata_file_id` (`file_id`),
  KEY `idx_file_metadata_bucket_status` (`bucket_name`, `status`),
  KEY `idx_file_metadata_file_name` (`file_name`),
  KEY `idx_file_metadata_create_time` (`create_time`),
  KEY `idx_file_metadata_md5` (`md5`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `document` (
  `id` VARCHAR(64) NOT NULL,
  `title` VARCHAR(255) NOT NULL,
  `content` LONGTEXT DEFAULT NULL,
  `summary` TEXT DEFAULT NULL,
  `keywords` TEXT DEFAULT NULL,
  `file_id` VARCHAR(64) DEFAULT NULL,
  `user_id` BIGINT DEFAULT NULL,
  `bucket_name` VARCHAR(128) DEFAULT NULL,
  `object_name` VARCHAR(512) DEFAULT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'active',
  `version` INT NOT NULL DEFAULT 1,
  `category` VARCHAR(50) DEFAULT NULL,
  `tags` TEXT DEFAULT NULL,
  `created_by` VARCHAR(64) DEFAULT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_document_user_id` (`user_id`),
  KEY `idx_document_bucket_name` (`bucket_name`),
  KEY `idx_document_status` (`status`),
  KEY `idx_document_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `document_access` (
  `id` VARCHAR(64) NOT NULL,
  `document_id` VARCHAR(64) NOT NULL,
  `bucket_name` VARCHAR(128) NOT NULL,
  `user_id` BIGINT NOT NULL,
  `role` VARCHAR(20) NOT NULL DEFAULT 'editor',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_document_access_document_user` (`document_id`, `user_id`),
  KEY `idx_document_access_user_bucket` (`user_id`, `bucket_name`),
  KEY `idx_document_access_bucket` (`bucket_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `document_version` (
  `id` VARCHAR(64) NOT NULL,
  `document_id` VARCHAR(64) NOT NULL,
  `version_number` INT NOT NULL,
  `title` VARCHAR(255) NOT NULL,
  `content` LONGTEXT DEFAULT NULL,
  `summary` TEXT DEFAULT NULL,
  `keywords` TEXT DEFAULT NULL,
  `change_log` VARCHAR(500) DEFAULT NULL,
  `created_by` VARCHAR(64) DEFAULT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_document_version_document_id` (`document_id`),
  KEY `idx_document_version_number` (`version_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `document_comment` (
  `id` VARCHAR(64) NOT NULL,
  `document_id` VARCHAR(64) NOT NULL,
  `user_id` BIGINT DEFAULT NULL,
  `content` TEXT DEFAULT NULL,
  `parent_id` VARCHAR(64) DEFAULT NULL,
  `created_by` VARCHAR(64) DEFAULT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `status` VARCHAR(20) NOT NULL DEFAULT 'active',
  PRIMARY KEY (`id`),
  KEY `idx_document_comment_document_id` (`document_id`),
  KEY `idx_document_comment_user_id` (`user_id`),
  KEY `idx_document_comment_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `document_annotation` (
  `id` VARCHAR(64) NOT NULL,
  `document_id` VARCHAR(64) NOT NULL,
  `user_id` BIGINT DEFAULT NULL,
  `line_number` INT DEFAULT NULL,
  `start_offset` INT DEFAULT NULL,
  `end_offset` INT DEFAULT NULL,
  `annotation_type` VARCHAR(50) DEFAULT NULL,
  `content` TEXT DEFAULT NULL,
  `color` VARCHAR(20) DEFAULT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'active',
  `created_by` VARCHAR(64) DEFAULT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_document_annotation_document_id` (`document_id`),
  KEY `idx_document_annotation_user_id` (`user_id`),
  KEY `idx_document_annotation_line_number` (`line_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `agent_memory` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `name` VARCHAR(64) NOT NULL,
  `type` VARCHAR(20) NOT NULL,
  `content` VARCHAR(500) NOT NULL,
  `document_id` VARCHAR(64) DEFAULT NULL,
  `source_task_id` VARCHAR(64) DEFAULT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_memory_user_name` (`user_id`, `name`),
  KEY `idx_agent_memory_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `agent_session` (
  `id` VARCHAR(64) NOT NULL,
  `user_id` BIGINT NOT NULL,
  `document_id` VARCHAR(64) DEFAULT NULL,
  `title` VARCHAR(64) DEFAULT NULL,
  `last_task_id` VARCHAR(64) DEFAULT NULL,
  `last_message_id` BIGINT NOT NULL DEFAULT 0,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_agent_session_user_update` (`user_id`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `agent_session_message` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `session_id` VARCHAR(64) NOT NULL,
  `task_id` VARCHAR(64) DEFAULT NULL,
  `role` VARCHAR(16) NOT NULL,
  `content` MEDIUMTEXT DEFAULT NULL,
  `tool_call_id` VARCHAR(64) DEFAULT NULL,
  `tool_name` VARCHAR(64) DEFAULT NULL,
  `tool_calls` MEDIUMTEXT DEFAULT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_agent_session_message_session` (`session_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `agent_session_summary` (
  `session_id` VARCHAR(64) NOT NULL,
  `summary_text` MEDIUMTEXT NOT NULL,
  `covered_to` BIGINT NOT NULL,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
