import {describe,it,expect} from 'vitest';
import {requestActions} from '../src/pages/Requests';

describe('received request actions',()=>{
 it('uses the server allowedActions as they are',()=>{
  expect(requestActions({allowedActions:['ACCEPT','REJECT'],status:'OPEN',ownerUserId:'me'},'me')).toEqual(['ACCEPT','REJECT']);
  expect(requestActions({allowedActions:['REPLY'],status:'ACCEPTED',ownerUserId:'me'},'me')).toEqual(['REPLY']);
  expect(requestActions({allowedActions:[],status:'OPEN',ownerUserId:'other'},'me')).toEqual([]);
 });
 it('falls back to reply-only for an older server without allowedActions',()=>{
  expect(requestActions({status:'OPEN',ownerUserId:'me'},'me')).toEqual(['REPLY']);
  expect(requestActions({status:'REPLIED',ownerUserId:'me'},'me')).toEqual([]);
 });
});
