ALTER TABLE query_cache ADD COLUMN query_type TEXT;
ALTER TABLE query_cache ADD COLUMN lat REAL;
ALTER TABLE query_cache ADD COLUMN lng REAL;
ALTER TABLE query_cache ADD COLUMN radius_meters INTEGER;
ALTER TABLE query_cache ADD COLUMN category TEXT;
