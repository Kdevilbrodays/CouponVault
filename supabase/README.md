# Supabase Migrations

1. Every schema change is a NEW file in `supabase/migrations/` named `<UTC_TIMESTAMP>_<description>.sql` (14-digit UTC timestamp YYYYMMDDHHMMSS, strictly increasing).
2. Never edit, rename, or delete an existing migration. Fix mistakes with a new migration.
3. All migrations must be idempotent, non-destructive, and safe to run in a single transaction (no `BEGIN`/`COMMIT`, no `CONCURRENTLY`).
4. Never use or reference the `service_role` key in migrations or client code.
5. Do NOT create `supabase/config.toml` and do not add a `[remotes]` block.
