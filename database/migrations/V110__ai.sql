USE orderflow;

CREATE TABLE IF NOT EXISTS ai_knowledge_documents (
  id VARCHAR(48) PRIMARY KEY,
  title VARCHAR(160) NOT NULL,
  body TEXT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  model VARCHAR(160) NOT NULL,
  vector_space CHAR(64) NOT NULL,
  dimension INT UNSIGNED NOT NULL CHECK (dimension BETWEEN 1 AND 4096),
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ai_knowledge_chunks (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  document_id VARCHAR(48) NOT NULL,
  ordinal INT UNSIGNED NOT NULL,
  title VARCHAR(160) NOT NULL,
  body TEXT NOT NULL,
  embedding BLOB NOT NULL,
  UNIQUE KEY (document_id, ordinal),
  FOREIGN KEY (document_id) REFERENCES ai_knowledge_documents(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ai_conversations (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  mode ENUM('AGENT','RAG') NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY (user_id, mode, updated_at),
  KEY (updated_at),
  FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ai_messages (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  conversation_id BIGINT UNSIGNED NOT NULL,
  speaker ENUM('user','assistant') NOT NULL,
  body TEXT NOT NULL,
  KEY (conversation_id, id),
  FOREIGN KEY (conversation_id) REFERENCES ai_conversations(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ai_tool_call_logs (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  conversation_id BIGINT UNSIGNED NOT NULL,
  tool VARCHAR(48) NOT NULL,
  arguments JSON NOT NULL,
  status ENUM('OK','BLOCKED','FAILED') NOT NULL,
  elapsed_ms INT UNSIGNED NOT NULL,
  KEY (conversation_id, id),
  FOREIGN KEY (conversation_id) REFERENCES ai_conversations(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ai_inventory_reports (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  merchant_id BIGINT UNSIGNED NOT NULL,
  snapshot JSON NOT NULL,
  advice JSON NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY (merchant_id, created_at),
  KEY (created_at),
  FOREIGN KEY (user_id) REFERENCES users(id),
  FOREIGN KEY (merchant_id) REFERENCES merchants(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ai_request_logs (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  operation VARCHAR(24) NOT NULL,
  model VARCHAR(160) NOT NULL,
  status VARCHAR(32) NOT NULL,
  elapsed_ms INT UNSIGNED NOT NULL DEFAULT 0,
  input_tokens INT UNSIGNED,
  output_tokens INT UNSIGNED,
  total_tokens INT UNSIGNED,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY (created_at),
  KEY (user_id, created_at),
  FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;
