import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {Registry} from '../src/registry.mjs';
import {instanceLock} from '../src/database.mjs';
import {verifyUpgrade} from '../scripts/verify-upgrade.mjs';

test('offline upgrade migrates active and suspended tenants without changing revisions or records',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'peygir-upgrade-'));
  t.after(()=>rmSync(dir,{recursive:true,force:true}));
  let registry=new Registry(dir);
  const expected=[];
  for(const [code,active] of [['active',true],['suspended',false]]) {
    const {company}=await registry.create({code,name:code,managerName:'Manager',managerPassword:'manager-password-12345'});
    registry.update(company.id,{name:code,active});
    const lease=registry.lease(company),id=randomUUID();
    try {
      lease.db.prepare('INSERT INTO records VALUES(?,?,?,?,?,?,?,?,?)').run(company.id,id,'meeting',1,JSON.stringify({title:code}),0,'Original creator',Date.now(),randomUUID());
    } finally {lease.release();}
    expected.push({company,id,code});
  }
  registry.close();
  assert.equal(verifyUpgrade(dir),2);
  assert.equal(verifyUpgrade(dir),2);
  registry=new Registry(dir);
  try {
    for(const {company,id,code} of expected) {
      const lease=registry.lease(company);
      try {
        const row=lease.db.prepare('SELECT * FROM records WHERE id=?').get(id);
        assert.equal(row.revision,1);assert.equal(row.actor,'Original creator');
        assert.deepEqual(JSON.parse(row.payload),{title:code,creator:'Original creator'});
      } finally {lease.release();}
    }
    assert.equal(registry.find('suspended').active,0);
  } finally {registry.close();}
});

test('offline upgrade refuses a running instance and an uninitialized installation',()=>{
  const dir=mkdtempSync(join(tmpdir(),'peygir-upgrade-'));
  try {
    assert.throws(()=>verifyUpgrade(dir),/Existing registry/);
    new Registry(dir).close();
    const unlock=instanceLock(dir);
    try {assert.throws(()=>verifyUpgrade(dir));} finally {unlock();}
    assert.equal(verifyUpgrade(dir),0);
  } finally {rmSync(dir,{recursive:true,force:true});}
});
