import {describe,it,expect} from 'vitest';
import {followupBlockReason} from '../src/components/ResultReview';

describe('result review use state',()=>{
 it('blocks follow-up only while an analysis runs, not while an approval waits',()=>{
  expect(followupBlockReason(null)).toBe('');
  expect(followupBlockReason({status:'SUCCEEDED'})).toBe('');
  expect(followupBlockReason({status:'QUEUED'})).toContain('진행 중');
  expect(followupBlockReason({status:'RUNNING'})).toContain('진행 중');
  expect(followupBlockReason({status:'WAITING_APPROVAL'})).toBe('');
 });
});
