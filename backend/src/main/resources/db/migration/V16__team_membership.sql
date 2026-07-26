-- Team membership, so an invite leads somewhere.
--
-- Inviting a teammate produced a token and a link to a page that did not exist,
-- and nothing consumed the token. Accepting now means something concrete: the
-- invitee gets their own credentials and is mapped onto the inviting account, so
-- their session is scoped to that account and they see the same workflows, keys
-- and usage. Tenancy elsewhere is unchanged — it still keys off one developer
-- id, which is now the account rather than the person.

CREATE TABLE IF NOT EXISTS account_memberships (
    member_developer_id  VARCHAR(64) PRIMARY KEY,
    account_developer_id VARCHAR(64)  NOT NULL,
    role                 VARCHAR(16)  NOT NULL DEFAULT 'MEMBER',
    invited_email        VARCHAR(200),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_membership_account ON account_memberships (account_developer_id);

-- An invite that never expires is a permanent key to someone's account, and one
-- that cannot be withdrawn is worse. Both are now possible.
ALTER TABLE team_invites ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
ALTER TABLE team_invites ADD COLUMN IF NOT EXISTS revoked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE team_invites ADD COLUMN IF NOT EXISTS accepted_by VARCHAR(64);
ALTER TABLE team_invites ADD COLUMN IF NOT EXISTS accepted_at TIMESTAMPTZ;

-- Invites issued before expiry existed are given one rather than left open
-- forever; they were never deliverable, so nothing in flight is broken.
UPDATE team_invites SET expires_at = created_at + INTERVAL '7 days' WHERE expires_at IS NULL;
