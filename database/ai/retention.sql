-- Run manually on the primary as the local operator after agreeing on evidence retention.
-- Messages and tool logs for removed conversations are deleted by their foreign keys.
DELETE FROM orderflow.ai_conversations WHERE updated_at < UTC_TIMESTAMP() - INTERVAL 30 DAY;
DELETE FROM orderflow.ai_inventory_reports WHERE created_at < UTC_TIMESTAMP() - INTERVAL 30 DAY;
DELETE FROM orderflow.ai_request_logs WHERE created_at < UTC_TIMESTAMP() - INTERVAL 90 DAY;
