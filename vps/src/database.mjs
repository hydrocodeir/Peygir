import {DatabaseSync} from 'node:sqlite';
import {mkdirSync, chmodSync} from 'node:fs';
import {join} from 'node:path';

export function openDatabase(path) {
  const db=new DatabaseSync(path);
  db.exec('PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA busy_timeout=5000;');
  try {chmodSync(path,0o600);} catch { /* Windows permissions are inherited. */ }
  return db;
}
class Statement {
  constructor(db,sql,args=[]) {this.db=db;this.sql=sql;this.args=args;}
  bind(...args) {return new Statement(this.db,this.sql,args);}
  async first(column) {const row=this.db.prepare(this.sql).get(...this.args);return row ? (column ? row[column] : {...row}) : null;}
  async all() {return {success:true,results:this.db.prepare(this.sql).all(...this.args).map(r=>({...r}))};}
  runSync() {const r=this.db.prepare(this.sql).run(...this.args);return {success:true,meta:{changes:Number(r.changes),last_row_id:Number(r.lastInsertRowid)}};}
  async run() {return this.runSync();}
}
/** Execute the existing D1 query contract against local SQLite, including atomic batches. */
export class SqliteD1 {
  constructor(db) {this.db=db;}
  prepare(sql) {return new Statement(this.db,sql);}
  async batch(statements) {
    this.db.exec('BEGIN IMMEDIATE');
    try {const result=statements.map(s=>{if(s.db!==this.db) throw new Error('Mixed database batch');return s.runSync();});this.db.exec('COMMIT');return result;}
    catch(e) {this.db.exec('ROLLBACK');throw e;}
  }
}
/** SQLite releases this process lock after crashes as well as clean shutdowns. */
export function instanceLock(dataDir) {
  mkdirSync(dataDir,{recursive:true,mode:0o700});
  const lock=new DatabaseSync(join(dataDir,'instance-lock.sqlite'));
  try {lock.exec('PRAGMA busy_timeout=0; BEGIN EXCLUSIVE; CREATE TABLE IF NOT EXISTS guard(id INTEGER);');}
  catch {lock.close();throw new Error('API is running or another maintenance operation holds the data lock. Stop the API before backup/restore.');}
  return ()=>{lock.exec('ROLLBACK');lock.close();};
}
