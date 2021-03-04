START TRANSACTION ISOLATION LEVEL SERIALIZABLE;

------ Locations ------

create schema location;

create type location.us_state as enum(
  'AL', 'AK', 'AZ', 'AR', 'CA', 'CO', 'CT', 'DE', 'FL', 'GA', 'HI', 'ID', 'IL', 'IN', 'IA', 'KS',
  'KY', 'LA', 'ME', 'MD', 'MA', 'MI', 'MN', 'MS', 'MO', 'MT', 'NE', 'NV', 'NH', 'NJ', 'NM', 'NY',
  'NC', 'ND', 'OH', 'OK', 'OR', 'PA', 'RI', 'SC', 'SD', 'TN', 'TX', 'UT', 'VT', 'VA', 'WA', 'WV',
  'WI', 'WY', 'DC', 'AS', 'GU', 'MP', 'PR', 'UM', 'VI');

create table location.locations (
  id            serial primary key,
  us_state      location.us_state not null,
  initial_name  varchar(256) unique not null,
  address       varchar(256) null,  -- I have no plan to handle changes to these 😅
  note          text null
);

create table location.names (
  location_id  integer references location.locations not null,
  name         varchar(1000) not null,
  ts           timestamp with time zone not null default now(),
  note         text null,
  primary key (location_id, name)
);

create index on location.names (location_id);
create index on location.names (name);

create view location.current_name as
select distinct on (location_id) location_id, name, ts, note
from location.names
order by location_id, ts DESC;

create table location.updates (
  location_id  serial primary key,
  ts           timestamp with time zone not null default now(),
  us_state     location.us_state not null,
  data         jsonb not null
);

-- TODO: should we have a similar index with the col order swapped?
create index on location.updates (ts, us_state);

------ Subscriptions ------

create schema subscription;

create table subscription.subscriptions (
  id         serial primary key,
  email      varchar(500) not null,
  
  -- If null, we’ll default to `en` in the app code. But I want to preserve that we don’t know the
  -- person’s actual preference.
  language   varchar(5) null CHECK (language is null or language ~* '^[a-zA-Z]{2}(-[a-zA-Z]{2})?$'),
  
  nonce      varchar(200) not null
);

create index on subscription.subscriptions (lower(email));

-- new: the verification email has not yet been sent
-- pending-verification: the verification email has been sent; the link in it has not yet been opened
-- active: the link in the verification email was opened; we will send notifications
-- canceled: the recipient has canceled the subscription; we will not send any emails at all
create type subscription.state as enum ('new', 'pending-verification', 'active', 'canceled');

create table subscription.state_changes (
  subscription_id  integer references subscription.subscriptions not null,
  ts               timestamp with time zone not null default now(),
  state            subscription.state not null,
  note             text null
);

create index on subscription.state_changes (subscription_id);

create view subscription.current_state as
select distinct on (subscription_id) subscription_id, state, ts, note
from subscription.state_changes
order by subscription_id, ts desc;

create view subscription.subscriptions_with_current_state as
select s.*, cs.state, cs.ts as state_change_ts, cs.note as state_change_note
from subscription.subscriptions s
  left join subscription.current_state cs on s.id = cs.subscription_id
order by cs.ts;

create table subscription.locations (
  subscription_id  integer references subscription.subscriptions not null,
  location_id      integer references location.locations not null,
  primary key (subscription_id, location_id)
);

create index on subscription.locations (subscription_id);
create index on subscription.locations (location_id);


------ Events ------

create schema event;

create table event.type (
  id    serial primary key,
  name  varchar(256) unique not null
);

create type event.subject_type as enum ('subscription', 'locations');

create table event.events (
  id                serial primary key,
  ts                timestamp with time zone not null default now(),
  event_type_id     integer references event.type,
  subject_type      event.subject_type not null,
  subscription_id   integer references subscription.subscriptions null,
  note              text
);

create table event.events_locations (
  event_id     integer references event.events,
  location_id  integer references location.locations not null,

  primary key (event_id, location_id)
);

-- TODO: should we have a similar index with the col order swapped?
create index on event.events (ts, event_type_id);

create index on event.events (subscription_id);

COMMIT TRANSACTION;
