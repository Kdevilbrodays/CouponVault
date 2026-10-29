-- 20261028173000_live_sync.sql
-- Live sync enablement: Realtime publication, server_now() for clock sync, and tombstone persistence policy
-- Safe to execute repeatedly (idempotent, single-transaction compatible)

-- 1. Enable Realtime updates for public.coupons
-- IMPORTANT: Remote tombstones (rows where deleted_at is not null) must NEVER be purged
-- from this table so that deletions reliably propagate to all other connected devices.
do $$
begin
  if not exists (
    select 1
    from pg_publication_tables
    where pubname = 'supabase_realtime'
      and schemaname = 'public'
      and tablename = 'coupons'
  ) then
    alter publication supabase_realtime add table public.coupons;
  end if;
end;
$$;

-- 2. Server time clock sync helper
create or replace function public.server_now()
returns timestamptz
language sql
stable
as $$
  select now();
$$;
