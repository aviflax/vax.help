------ Locations ------

create schema location;

create type us_state as enum(
  'AL', 'AK', 'AZ', 'AR', 'CA', 'CO', 'CT', 'DE', 'FL', 'GA', 'HI', 'ID', 'IL', 'IN', 'IA', 'KS',
  'KY', 'LA', 'ME', 'MD', 'MA', 'MI', 'MN', 'MS', 'MO', 'MT', 'NE', 'NV', 'NH', 'NJ', 'NM', 'NY',
  'NC', 'ND', 'OH', 'OK', 'OR', 'PA', 'RI', 'SC', 'SD', 'TN', 'TX', 'UT', 'VT', 'VA', 'WA', 'WV',
  'WI', 'WY', 'DC', 'AS', 'GU', 'MP', 'PR', 'UM', 'VI');

create table location.locations (
  id        serial primary key,
  us_state  us_state not null,
  note      text null
);

create table location.names (
  id    integer references location.locations not null,
  name  varchar(1000) not null,
  ts    timestamp with time zone not null default now(),
  note  text null,
  primary key (id, name)
);

create index on location.names (id);
create index on location.names (name);

create view location.current_name as
select distinct on (id) id, name, ts, note
from location.names
order by id, ts DESC;


------ Subscriptions ------

create schema subscription;

create table subscription.requests (
  id              serial primary key,
  ts              timestamp with time zone not null default now(),
  email           varchar(500) not null,
  language        varchar(5) null CHECK (language is null or language ~* '^[a-zA-Z]{2}(-[a-zA-Z]{2})?$'), -- if null, we default to en
  location_names  text not null
);

comment on table subscription.requests is 'When we get a request for a subcription, we throw it in'
                                          ' this simplistic table as fast as possible, just in case'
                                          ' the load on the DB would be too great for doing all the'
                                          ' various inserts etc that’d be needed to properly add'
                                          ' the subscription to the subscriptions table, which'
                                          ' entails checking constraints, updating indices, etc.';

create table subscription.subscriptions (
  id         serial primary key,
  request_id integer references subscription.requests not null,
  email      varchar(500) not null unique,
  language   varchar(5) null CHECK (language is null or language ~* '^[a-zA-Z]{2}(-[a-zA-Z]{2})?$'), -- if null, we default to en
  nonce      varchar(200) not null
);

create unique index on subscription.subscriptions (lower(email));
create index on subscription.subscriptions (request_id);

create type subscription_state as enum ('unverified+inactive', 'verified+active', 'canceled');

create table subscription.state_changes (
  subscription_id  integer references subscription.subscriptions not null,
  ts               timestamp with time zone not null default now(),
  state            subscription_state not null,
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
