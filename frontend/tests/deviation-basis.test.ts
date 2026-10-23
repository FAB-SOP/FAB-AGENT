import {describe,it,expect} from 'vitest';
import {deviationBasis} from '../src/components/InvestigationContext';

describe('brief deviation basis',()=>{
 it('shows control limits instead of a normal mean for control-limit metrics',()=>{
  expect(deviationBasis({thresholdKind:'CONTROL_LIMIT',baselineMean:10001.9,z:null,lcl:9870.3,ucl:10133.5}))
   .toBe('관리 중심 10,001.9 · 관리한계 9,870.3~10,133.5');
 });
 it('shows normal mean and sigma for same-PM baselines, and a dash when unknown',()=>{
  expect(deviationBasis({thresholdKind:'SAME_PM_BASELINE',baselineMean:4999.43,z:-17.33})).toBe('정상 평균 4,999.430 / -17.33σ');
  expect(deviationBasis({thresholdKind:'SAME_PM_BASELINE',baselineMean:null,z:null})).toBe('—');
 });
});
