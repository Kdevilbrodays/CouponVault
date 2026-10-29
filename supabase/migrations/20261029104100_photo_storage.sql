-- 20261029104100_photo_storage.sql
-- Create private storage bucket 'coupon-photos' and RLS policies for authenticated users
-- Object path convention: "<user uid>/<syncId>.jpg"
-- Safe to execute repeatedly (idempotent, single-transaction compatible)

-- 1. Create the private bucket 'coupon-photos'
insert into storage.buckets (id, name, public)
values ('coupon-photos', 'coupon-photos', false)
on conflict (id) do nothing;

-- 2. Storage RLS Policies for authenticated users

-- (a) SELECT policy: authenticated users can read their own photos
drop policy if exists "Authenticated users can select own coupon photos" on storage.objects;
create policy "Authenticated users can select own coupon photos"
on storage.objects
for select
to authenticated
using (
  bucket_id = 'coupon-photos'
  and (storage.foldername(name))[1] = auth.uid()::text
);

-- (b) INSERT policy: authenticated users can upload photos into their own folder
drop policy if exists "Authenticated users can insert own coupon photos" on storage.objects;
create policy "Authenticated users can insert own coupon photos"
on storage.objects
for insert
to authenticated
with check (
  bucket_id = 'coupon-photos'
  and (storage.foldername(name))[1] = auth.uid()::text
);

-- (c) UPDATE policy: authenticated users can update photos in their own folder
drop policy if exists "Authenticated users can update own coupon photos" on storage.objects;
create policy "Authenticated users can update own coupon photos"
on storage.objects
for update
to authenticated
using (
  bucket_id = 'coupon-photos'
  and (storage.foldername(name))[1] = auth.uid()::text
)
with check (
  bucket_id = 'coupon-photos'
  and (storage.foldername(name))[1] = auth.uid()::text
);

-- (d) DELETE policy: authenticated users can delete photos in their own folder
drop policy if exists "Authenticated users can delete own coupon photos" on storage.objects;
create policy "Authenticated users can delete own coupon photos"
on storage.objects
for delete
to authenticated
using (
  bucket_id = 'coupon-photos'
  and (storage.foldername(name))[1] = auth.uid()::text
);
