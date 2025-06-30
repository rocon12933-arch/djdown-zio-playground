
/* Sqlite Style*/
--state: 0:pending 1:waiting_for_handlers 2:running 3:partially_completed 4:completed 
--state: -1:interrupted -2:failed
create table manga_meta(
  id integer primary key autoincrement,
  gallery_uri nvarchar(500) not null,
  is_parsed boolean not null default false,
  title nvarchar(500),
  total_pages int not null default 0,
  completed_pages int not null default 0,
  state smallint not null,
  cause nvarchar(500),
  extra nvarchar(500),
  created_at timestamp not null default CURRENT_TIMESTAMP
);


--status: 0:pending 1:running 2:completed -1:interrupted -2:failed
create table manga_page(
  id integer primary key autoincrement,
  page_uri nvarchar(500) not null,
  meta_id int not null,
  page_number int not null,
  title nvarchar(500) not null,
  state smallint not null,
  created_at timestamp not null default CURRENT_TIMESTAMP
);
