import {describe,it,expect} from 'vitest';
import {canViewCauseStatistics} from '../src/domain/permissions';

const session=(role:string,team:string)=>({role,user:{team}}) as any;
describe('cause statistics access',()=>{
 it('allows managers and maintenance engineers only',()=>{
  expect(canViewCauseStatistics(session('MANAGER','전체 관리자'))).toBe(true);
  expect(canViewCauseStatistics(session('ENGINEER','설비정비팀'))).toBe(true);
  expect(canViewCauseStatistics(session('ENGINEER','연마기술팀'))).toBe(false);
  expect(canViewCauseStatistics(null)).toBe(false);
 });
});
