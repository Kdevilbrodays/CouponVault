-- 20261029114000_claim.sql
-- Multi-device queue claim & release functions to prevent duplicate paste distribution
-- Safe to re-run (idempotent, single transaction, security invoker)

-- 1. Ensure claim tracking columns exist
alter table public.coupons add column if not exists claimed_by text;
alter table public.coupons add column if not exists claimed_at timestamptz;

-- 2. Index to accelerate claim queue selection
create index if not exists coupons_claim_queue_idx on public.coupons (owner, status, created_at)
  where deleted_at is null;

-- 3. Atomic claim function: selects the oldest live unscanned coupon and locks it
create or replace function public.claim_next_coupon(p_device text)
returns setof public.coupons
language plpgsql
security invoker
as $$
declare
  v_coupon public.coupons%rowtype;
begin
  select *
  into v_coupon
  from public.coupons
  where owner = auth.uid()
    and deleted_at is null
    and status = 'UNSCANNED'
    and code is not null
    and trim(code) <> ''
    and (claimed_by is null or claimed_at < now() - interval '30 minutes')
  order by created_at asc
  limit 1
  for update skip locked;

  if not found then
    return;
  end if;

  update public.coupons
  set claimed_by = p_device,
      claimed_at = now()
  where id = v_coupon.id
  returning * into v_coupon;

  return next v_coupon;
  return;
end;
$$;

-- 4. Release function: frees claimed status for a coupon owned by current user
create or replace function public.release_coupon(p_id uuid)
returns void
language plpgsql
security invoker
as $$
begin
  update public.coupons
  set claimed_by = null,
      claimed_at = null
  where id = p_id
    and owner = auth.uid();
end;
$$;

-- 5. Permissions
grant execute on function public.claim_next_coupon(text) to authenticated;
grant execute on function public.release_coupon(uuid) to authenticated;
