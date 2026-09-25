-- Sessions are stateless signed tokens. This is the one piece of state that
-- lets them be ended early: a session issued before this moment is refused.
-- Moved forward by a password change and by "sign out everywhere else".
ALTER TABLE developer_auth ADD COLUMN sessions_valid_after TIMESTAMP WITH TIME ZONE NULL;
