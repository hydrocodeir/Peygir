PRAGMA foreign_keys = ON;
CREATE TABLE workspaces(id TEXT PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE users(id TEXT PRIMARY KEY, workspace TEXT NOT NULL REFERENCES workspaces(id), username TEXT NOT NULL UNIQUE, name TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('assistant','manager')), salt TEXT NOT NULL, password_hash TEXT NOT NULL, iterations INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1);
CREATE TABLE sessions(token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), expires INTEGER NOT NULL);
CREATE INDEX session_expiry ON sessions(expires);
CREATE TABLE login_limits(key TEXT PRIMARY KEY, count INTEGER NOT NULL, expires INTEGER NOT NULL);
CREATE TABLE records(workspace TEXT NOT NULL REFERENCES workspaces(id), id TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('meeting','task','followup','history')), revision INTEGER NOT NULL, payload TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, actor TEXT NOT NULL, changed INTEGER NOT NULL, operation TEXT NOT NULL, PRIMARY KEY(workspace,id));
CREATE TABLE changes(seq INTEGER PRIMARY KEY AUTOINCREMENT, workspace TEXT NOT NULL, id TEXT NOT NULL, kind TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL, deleted INTEGER NOT NULL, actor TEXT NOT NULL, changed INTEGER NOT NULL);
CREATE INDEX changes_workspace ON changes(workspace,seq);
CREATE TABLE operations(workspace TEXT NOT NULL, id TEXT NOT NULL, fingerprint TEXT NOT NULL, response TEXT NOT NULL, PRIMARY KEY(workspace,id));
CREATE TRIGGER record_created AFTER INSERT ON records BEGIN
  INSERT INTO changes(workspace,id,kind,revision,payload,deleted,actor,changed) VALUES(NEW.workspace,NEW.id,NEW.kind,NEW.revision,NEW.payload,NEW.deleted,NEW.actor,NEW.changed);
END;
CREATE TRIGGER record_updated AFTER UPDATE ON records WHEN OLD.revision<>NEW.revision BEGIN
  INSERT INTO changes(workspace,id,kind,revision,payload,deleted,actor,changed) VALUES(NEW.workspace,NEW.id,NEW.kind,NEW.revision,NEW.payload,NEW.deleted,NEW.actor,NEW.changed);
END;
