-- =====================================================================
-- CyberQalqan — қауымдастықтың қауіп базасы (Supabase / PostgreSQL)
-- Supabase → SQL Editor → осы файлды толық қойып → Run.
-- Қайта іске қосуға болады (идемпотентті).
--
-- ҚҰПИЯЛЫЛЫҚ: SMS мәтіні, телефон нөмірі, толық сілтеме, аты-жөні САҚТАЛМАЙДЫ.
-- Тек: домен, APK SHA-256 хэші, APK пакет аты, қауіп санаты, баға, уақыт.
-- Кестелерге тікелей қол жеткізу жабық (RLS, саясатсыз) — тек төмендегі функциялар арқылы.
-- =====================================================================

create table if not exists public.threat_reports (
  id          bigint generated always as identity primary key,
  kind        text        not null check (kind in ('domain','apk_sha256','apk_package')),
  value       text        not null check (char_length(value) between 3 and 200),
  category    text        not null default 'phishing' check (category in
              ('phishing','fake_bank','sms_stealer','credential_phishing','rat','spyware','overlay','crypto_scam','remote_access','other')),
  score       smallint    not null check (score between 0 and 100),
  source      text        not null default 'auto' check (source in ('auto','user')),
  client      text        not null default '' check (char_length(client) <= 64),   -- серверде тұздалған IP хэші (IP өзі емес)
  created_at  timestamptz not null default now()
);
create index if not exists threat_reports_kv_idx      on public.threat_reports (kind, value);
create index if not exists threat_reports_created_idx on public.threat_reports (created_at);
create index if not exists threat_reports_client_idx  on public.threat_reports (client, created_at);

create table if not exists public.check_stats (
  day        date    not null default current_date,
  channel    text    not null check (channel in ('web','android','apk','bubble')),
  checks     integer not null default 0,
  dangerous  integer not null default 0,
  primary key (day, channel)
);

-- RLS қосулы, саясат жоқ → anon/authenticated кестелерді тікелей оқи да, жаза да алмайды
alter table public.threat_reports enable row level security;
alter table public.check_stats    enable row level security;
revoke all on public.threat_reports from anon, authenticated;
revoke all on public.check_stats    from anon, authenticated;

-- ---------- Мәнді тексеру және қалыпқа келтіру ----------
create or replace function public.cq_norm(p_kind text, p_value text)
returns text language plpgsql immutable set search_path = '' as $$
declare v text := lower(btrim(coalesce(p_value, '')));
begin
  if p_kind = 'domain' then
    v := regexp_replace(v, '^https?://', '');
    v := split_part(split_part(split_part(v, '/', 1), '?', 1), '#', 1);
    v := regexp_replace(v, '^www\.', '');
    v := regexp_replace(v, ':\d+$', '');
    if v !~ '^[a-z0-9-]+(\.[a-z0-9-]+)+$' or char_length(v) > 200 then return null; end if;
  elsif p_kind = 'apk_sha256' then
    if v !~ '^[a-f0-9]{64}$' then return null; end if;
  elsif p_kind = 'apk_package' then
    if v !~ '^[a-z0-9_]+(\.[a-z0-9_]+)+$' or char_length(v) > 200 then return null; end if;
  else
    return null;
  end if;
  return v;
end $$;

-- ---------- Қауіп туралы хабарлау ----------
create or replace function public.report_threat(
  p_kind text, p_value text, p_category text default 'phishing',
  p_score integer default 80, p_source text default 'auto', p_client text default ''
) returns json language plpgsql security definer set search_path = '' as $$
declare
  v   text := public.cq_norm(p_kind, p_value);
  c   text := coalesce(nullif(left(p_client, 64), ''), 'anon');
  cat text := case when p_category in ('phishing','fake_bank','sms_stealer','credential_phishing','rat','spyware','overlay','crypto_scam','remote_access','other')
                   then p_category else 'other' end;
  src text := case when p_source = 'user' then 'user' else 'auto' end;
begin
  if v is null then return json_build_object('ok', false, 'error', 'invalid'); end if;
  if p_score is null or p_score < 40 then return json_build_object('ok', false, 'error', 'low_score'); end if;
  -- Жылдамдық шегі: бір клиенттен минутына 30-дан артық емес
  if (select count(*) from public.threat_reports where client = c and created_at > now() - interval '1 minute') >= 30 then
    return json_build_object('ok', false, 'error', 'rate_limited');
  end if;
  -- Бір клиент бір мәнді тәулігіне бір рет қана санайды
  if exists (select 1 from public.threat_reports where client = c and kind = p_kind and value = v and created_at > now() - interval '1 day') then
    return json_build_object('ok', true, 'duplicate', true);
  end if;
  insert into public.threat_reports (kind, value, category, score, source, client)
  values (p_kind, v, cat, least(greatest(p_score, 0), 100), src, c);
  return json_build_object('ok', true);
end $$;

-- ---------- Мәнді базадан тексеру ----------
create or replace function public.check_threat(p_kind text, p_value text)
returns json language plpgsql stable security definer set search_path = '' as $$
declare v text := public.cq_norm(p_kind, p_value); r record;
begin
  if v is null then return json_build_object('ok', false, 'error', 'invalid'); end if;
  select count(*)::int as reports, count(distinct client)::int as reporters,
         coalesce(max(score), 0)::int as max_score, coalesce(round(avg(score)), 0)::int as avg_score,
         coalesce(array_agg(distinct category) filter (where category is not null), '{}') as categories,
         min(created_at) as first_seen, max(created_at) as last_seen
    into r from public.threat_reports where kind = p_kind and value = v;
  return json_build_object('ok', true, 'kind', p_kind, 'value', v, 'reports', r.reports, 'reporters', r.reporters,
    'max_score', r.max_score, 'avg_score', r.avg_score, 'categories', r.categories,
    'first_seen', r.first_seen, 'last_seen', r.last_seen);
end $$;

-- ---------- Анонимді статистика (тек санауыштар) ----------
create or replace function public.log_check(p_channel text, p_dangerous boolean default false)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if p_channel not in ('web','android','apk','bubble') then return; end if;
  insert into public.check_stats (day, channel, checks, dangerous)
  values (current_date, p_channel, 1, case when p_dangerous then 1 else 0 end)
  on conflict (day, channel) do update
    set checks = public.check_stats.checks + 1,
        dangerous = public.check_stats.dangerous + excluded.dangerous;
end $$;

create or replace function public.get_stats()
returns json language sql stable security definer set search_path = '' as $$
  select json_build_object(
    'today_checks',    coalesce((select sum(checks)    from public.check_stats where day = current_date), 0),
    'today_dangerous', coalesce((select sum(dangerous) from public.check_stats where day = current_date), 0),
    'total_checks',    coalesce((select sum(checks)    from public.check_stats), 0),
    'total_dangerous', coalesce((select sum(dangerous) from public.check_stats), 0),
    'threats_known',   (select count(distinct (kind, value)) from public.threat_reports),
    'apk_known',       (select count(distinct value) from public.threat_reports where kind = 'apk_sha256'),
    'categories_30d',  coalesce((select json_object_agg(category, n) from (
                          select category, count(distinct (kind, value)) as n from public.threat_reports
                          where created_at > now() - interval '30 days' group by category) t), '{}'::json)
  );
$$;

-- Функцияларды тек anon/authenticated шақыра алады (кестелерге тікелей емес)
revoke all on function public.report_threat(text, text, text, integer, text, text) from public;
revoke all on function public.check_threat(text, text) from public;
revoke all on function public.log_check(text, boolean) from public;
revoke all on function public.get_stats() from public;
grant execute on function public.report_threat(text, text, text, integer, text, text) to anon, authenticated;
grant execute on function public.check_threat(text, text) to anon, authenticated;
grant execute on function public.log_check(text, boolean) to anon, authenticated;
grant execute on function public.get_stats() to anon, authenticated;
