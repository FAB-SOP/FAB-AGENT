import {describe,it,expect} from 'vitest';
import {resultUse} from '../src/components/ResultReview';

describe('result review use state',()=>{
 it('reads only the server analysisUse',()=>{
  expect(resultUse({analysisUse:{successfulCount:0,activeRequestIds:[]}}).label).toBe('미반영');
  expect(resultUse({analysisUse:{successfulCount:2,activeRequestIds:[]}}).label).toBe('분석 반영 2회');
  expect(resultUse({analysisUse:{successfulCount:1,activeRequestIds:['ar_1']}}).code).toBe('running');
  expect(resultUse({}).code).toBe('pending');
 });
});
