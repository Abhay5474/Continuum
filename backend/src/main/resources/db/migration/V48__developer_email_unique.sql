-- One account per email address, case-insensitively.
--
-- Account creation checked for an existing email and then inserted, so two
-- sign-ups arriving together both passed the check (12 concurrent requests
-- made 10 accounts). The application now serialises creation per address
-- (EmailLock); this index makes it a guarantee.
--
-- Additive only: if a deployment already holds duplicate addresses, the index
-- is not created and nothing is changed or removed — resolving which duplicate
-- to keep is an operator's decision, not a migration's. The application-level
-- lock still prevents new duplicates either way.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM developers WHERE email IS NOT NULL
        GROUP BY lower(email) HAVING count(*) > 1
    ) THEN
        CREATE UNIQUE INDEX IF NOT EXISTS uq_developers_email_lower ON developers (lower(email));
    ELSE
        RAISE NOTICE 'developers has duplicate emails; uq_developers_email_lower not created';
    END IF;
END $$;
