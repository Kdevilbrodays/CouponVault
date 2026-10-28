-- 001_coupons_sync.sql
-- CouponVault Supabase Multi-Device Sync Schema & RPC Functions
-- Safe to execute repeatedly (idempotent)

-- 1. Coupons table
create table if not exists public.coupons (
  id uuid primary key,
  owner uuid not null default auth.uid() references auth.users(id) on delete cascade,
  code text,
  status text not null default 'NEEDS_REVIEW' check (status in ('UNSCANNED','REDEEMED','FAILED','NEEDS_REVIEW')),
  source text not null default 'QR' check (source in ('QR','MANUAL')),
  extraction_state text not null default 'DONE' check (extraction_state in ('PENDING','DONE')),
  created_at timestamptz not null default now(),
  redeemed_at timestamptz,
  modified_at timestamptz not null default now(),
  deleted_at timestamptz,
  image_path text,
  image_sha256 text,
  image_variant text check (image_variant in ('original','reduced')),
  claimed_by text,
  claimed_at timestamptz,
  updated_at timestamptz not null default now()
);

-- Idempotent column additions for forward-compatibility
alter table public.coupons add column if not exists extraction_state text not null default 'DONE' check (extraction_state in ('PENDING','DONE'));
alter table public.coupons add column if not exists image_path text;
alter table public.coupons add column if not exists image_sha256 text;
alter table public.coupons add column if not exists image_variant text check (image_variant in ('original','reduced'));
alter table public.coupons add column if not exists claimed_by text;
alter table public.coupons add column if not exists claimed_at timestamptz;

-- 2. Indexes
create unique index if not exists coupons_owner_code_uniq on public.coupons (owner, code)
  where code is not null and code <> '' and deleted_at is null;

create index if not exists coupons_owner_updated_idx on public.coupons (owner, updated_at);

-- 3. Touch updated_at trigger (server time pull cursor)
create or replace function public.touch_updated_at() returns trigger language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

drop trigger if exists coupons_touch on public.coupons;
create trigger coupons_touch
  before insert or update on public.coupons
  for each row execute function public.touch_updated_at();

-- 4. Row Level Security (RLS)
alter table public.coupons enable row level security;

drop policy if exists coupons_select_policy on public.coupons;
create policy coupons_select_policy on public.coupons
  for select using (owner = auth.uid());

drop policy if exists coupons_insert_policy on public.coupons;
create policy coupons_insert_policy on public.coupons
  for insert with check (owner = auth.uid());

drop policy if exists coupons_update_policy on public.coupons;
create policy coupons_update_policy on public.coupons
  for update using (owner = auth.uid()) with check (owner = auth.uid());

drop policy if exists coupons_delete_policy on public.coupons;
create policy coupons_delete_policy on public.coupons
  for delete using (owner = auth.uid());

-- 5. Push RPC: push_coupons(items jsonb)
create or replace function public.push_coupons(items jsonb)
returns jsonb
language plpgsql
security invoker
as $$
declare
  v_item jsonb;
  v_id uuid;
  v_code text;
  v_status text;
  v_source text;
  v_extraction_state text;
  v_created_at timestamptz;
  v_redeemed_at timestamptz;
  v_mod_raw timestamptz;
  v_mod timestamptz;
  v_deleted_at timestamptz;
  v_image_path text;
  v_image_sha256 text;
  v_image_variant text;
  v_claimed_by text;
  v_claimed_at timestamptz;
  v_existing record;
  v_existing_id uuid;
  v_updated_at timestamptz;
  v_results jsonb := '[]'::jsonb;
begin
  if items is null or jsonb_array_length(items) = 0 then
    return v_results;
  end if;

  for v_item in select * from jsonb_array_elements(items)
  loop
    v_id := coalesce(nullif(v_item->>'id',''), nullif(v_item->>'sync_id',''))::uuid;
    v_code := nullif(trim(v_item->>'code'), '');
    v_status := coalesce(v_item->>'status', 'NEEDS_REVIEW');
    v_source := coalesce(v_item->>'source', 'QR');
    v_extraction_state := coalesce(v_item->>'extraction_state', 'DONE');
    v_created_at := coalesce((v_item->>'created_at')::timestamptz, now());
    v_redeemed_at := (v_item->>'redeemed_at')::timestamptz;
    v_mod_raw := coalesce((v_item->>'modified_at')::timestamptz, now());
    -- Clamp client timestamp to at most now() + 5 minutes
    v_mod := least(v_mod_raw, now() + interval '5 minutes');
    v_deleted_at := (v_item->>'deleted_at')::timestamptz;
    v_image_path := v_item->>'image_path';
    v_image_sha256 := v_item->>'image_sha256';
    v_image_variant := v_item->>'image_variant';

    -- Clear claims when status is not UNSCANNED
    if v_status = 'UNSCANNED' then
      v_claimed_by := v_item->>'claimed_by';
      v_claimed_at := (v_item->>'claimed_at')::timestamptz;
    else
      v_claimed_by := null;
      v_claimed_at := null;
    end if;

    begin
      select * into v_existing
      from public.coupons
      where id = v_id and owner = auth.uid();

      if found then
        -- Last-writer-wins comparison
        if v_mod >= v_existing.modified_at then
          update public.coupons
          set code = v_code,
              status = v_status,
              source = v_source,
              extraction_state = v_extraction_state,
              created_at = v_created_at,
              redeemed_at = v_redeemed_at,
              modified_at = v_mod,
              deleted_at = v_deleted_at,
              image_path = coalesce(v_image_path, public.coupons.image_path),
              image_sha256 = coalesce(v_image_sha256, public.coupons.image_sha256),
              image_variant = coalesce(v_image_variant, public.coupons.image_variant),
              claimed_by = v_claimed_by,
              claimed_at = v_claimed_at
          where id = v_id and owner = auth.uid()
          returning updated_at into v_updated_at;

          v_results := v_results || jsonb_build_object(
            'id', v_id,
            'sync_id', v_id,
            'status', 'applied',
            'result', 'applied',
            'updated_at', v_updated_at
          );
        else
          v_results := v_results || jsonb_build_object(
            'id', v_id,
            'sync_id', v_id,
            'status', 'stale',
            'result', 'stale',
            'updated_at', v_existing.updated_at
          );
        end if;
      else
        insert into public.coupons (
          id, owner, code, status, source, extraction_state,
          created_at, redeemed_at, modified_at, deleted_at,
          image_path, image_sha256, image_variant,
          claimed_by, claimed_at
        ) values (
          v_id, auth.uid(), v_code, v_status, v_source, v_extraction_state,
          v_created_at, v_redeemed_at, v_mod, v_deleted_at,
          v_image_path, v_image_sha256, v_image_variant,
          v_claimed_by, v_claimed_at
        )
        returning updated_at into v_updated_at;

        v_results := v_results || jsonb_build_object(
          'id', v_id,
          'sync_id', v_id,
          'status', 'applied',
          'result', 'applied',
          'updated_at', v_updated_at
        );
      end if;

    exception when unique_violation then
      -- Unique code violation: look up live row with same code for this owner
      select id, updated_at into v_existing_id, v_updated_at
      from public.coupons
      where owner = auth.uid()
        and code = v_code
        and deleted_at is null
      limit 1;

      v_results := v_results || jsonb_build_object(
        'id', v_id,
        'sync_id', v_id,
        'status', 'duplicate',
        'result', 'duplicate',
        'existing_id', v_existing_id,
        'updated_at', v_updated_at
      );
    end;
  end loop;

  return v_results;
end;
$$;
