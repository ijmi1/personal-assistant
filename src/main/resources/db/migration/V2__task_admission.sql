alter table tasks alter column created_at set default clock_timestamp();
create index tasks_ai_created_at on tasks (created_at) where type in ('QUESTION', 'DRAFT');
