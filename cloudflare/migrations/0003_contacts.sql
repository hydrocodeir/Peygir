CREATE TABLE IF NOT EXISTS contacts(workspace TEXT NOT NULL REFERENCES workspaces(id), key TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(workspace,key));
