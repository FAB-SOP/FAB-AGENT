import {describe,it,expect} from 'vitest';
import {reasonRequired} from '../src/components/WorkItems';

describe('work decision reasons',()=>{
 it('requires a reason for a manager rejection or unperformed closure',()=>{
  expect(reasonRequired('decline')).toBe(false);
  expect(reasonRequired('reject')).toBe(true);
  expect(reasonRequired('approve')).toBe(false);
  expect(reasonRequired('adopt')).toBe(false);
  expect(reasonRequired('unperformed')).toBe(true);
 });
});
